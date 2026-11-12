package dk.itu.datasys;

import java.io.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;
import java.util.stream.Stream;

import static java.util.Objects.requireNonNull;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dk.itu.datasys.Spec.*;

public final class StorageEngine {
    private static final Logger LOGGER = LoggerFactory.getLogger(StorageEngine.class);

    public static record ScanStats(int partitionsTotal, int partitionsRead, int partitionsPruned) {}

    private static final String catalogFileName = "catalog.json";
    private static final String catalogLockFileName = "catalog.lock";
    private static String tempCatalogFileName() {
        int random = ThreadLocalRandom.current().nextInt(0, 10000);
        return "catalog_temp_" + String.format("%04d", random) + ".json";
    }

    public static String partitionFileName(String tableName, int partitionIdx) {
        return tableName + "-part-" + partitionIdx + ".bin";
    }

    private enum CatalogState {
        NONE, OPEN_CLEAN, OPEN_DIRTY;

        boolean isOpen() {
            return this == OPEN_CLEAN || this == OPEN_DIRTY;
        }
    }

    private CatalogState catalogState = CatalogState.NONE;

    private final Path dataDir;
    private final Path catalogPath;
    private final ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private final int maxRowsPerPartition;

    // ---------- Public API ----------

    /**
     * @param dataDir the directory where all persistent state (catalog + data files) lives under
     */
    public StorageEngine(Path dataDir) {
        requireNonNull(dataDir, "dataDir");

        this.dataDir = dataDir;
        this.catalogPath = dataDir.resolve(catalogFileName);
        this.maxRowsPerPartition = Integer.getInteger("maxRowsPerPartition", 1000);

        try {
            Files.createDirectories(dataDir);
        } catch (IOException e) {
            throw new RuntimeException("cannot create data dir: " + dataDir, e);
        }
    }

    /**
     * Deletes all data files and the catalog file in the given data directory.
     * This is useful for playing with the engine in a test environment.
     * @param dataDir the directory where all persistent state (catalog + data files) lives under
     */
    public static void deleteAllData(Path dataDir, Function<List<String>, Boolean> confirm) {
        requireNonNull(dataDir, "dataDir");

        Function<Stream<Path>, Stream<File>> streamFilter = walker ->
            walker.sorted(Comparator.reverseOrder())
                .filter(path -> {
                    String name = path.toString();
                    return name.endsWith(".json")
                        || name.endsWith(".lock")
                        || name.endsWith(".bin");
                })
                .map(Path::toFile)
                .filter(File::isFile);

        try {
            List<String> dataPaths = streamFilter
                .apply(Files.walk(dataDir))
                .map(File::getName)
                .toList();

            if (!confirm.apply(dataPaths))
                return;

            streamFilter
                .apply(Files.walk(dataDir))
                .forEach(file -> {
                    file.delete();
                    LOGGER.debug("deleted data file: {}", file);
                });
        } catch (IOException e) {
            throw new RuntimeException("failed to delete data dir: " + dataDir, e);
        }
    }

    /**
     * Ensures that the catalog contains a table with the given name and columns.
     * @param tableName the name of the table to create
     * @param columns the list of columns for the table
     */
    public void createTable(Catalog catalog, String tableName, List<ColumnSpec> columns) {
        markCatalog(); 

        requireNonNull(tableName, "tableName");
        requireNonNull(columns, "columns");

        assert !columns.isEmpty();

        long startMs = System.currentTimeMillis();

        if (catalog.tableByName(tableName).isPresent())
            throw new IllegalArgumentException("table already exists: " + tableName);

        // check duplicate column names
        HashSet<String> names = new HashSet<>();
        for (ColumnSpec c : columns) {
            if (!names.add(c.name()))
                throw new IllegalArgumentException("duplicate column name: " + c.name());
        }

        catalog.tables.add(new TableMeta(tableName, new ArrayList<>(columns)));

        long durationMs = System.currentTimeMillis() - startMs;
        LOGGER.debug("created table table={} cols={} durationMs={}", tableName, columns.size(), durationMs);
    }

    /**
     * Copies the data from a CSV file into an empty table.
     * The CSV file must have the same number of columns as the table, and the column types must match.
     * @param tableIndex the index of the table to copy data into
     * @param csvFilePath the path to the CSV file
     */
    public void copyFromCsvFile(Catalog catalog, int tableIndex, Path csvFilePath) {
        markCatalog(); 

        requireNonNull(csvFilePath);

        long startMs = System.currentTimeMillis();

        TableMeta table = catalog.tables.get(tableIndex);

        table.version++;

        if (!Files.exists(csvFilePath))
            throw new IllegalArgumentException("csv file not found: " + csvFilePath);

        int totalRows = 0;
        int previousNextPartitionNumber = table.nextPartitionNumber;

        try (BufferedReader r = Files.newBufferedReader(csvFilePath, StandardCharsets.US_ASCII)) {
            String line;
            List<Value[]> buffer = new ArrayList<>();
            while ((line = r.readLine()) != null) {
                totalRows++;

                Value[] parsed = switch (parseCsvLine(line, table.columns)) {
                    case ParseCsvLineResult.Success(Value[] row) -> row;
                    case ParseCsvLineResult.Malformed m ->
                        throw m.toRuntimeException(csvFilePath, totalRows);
                };

                buffer.add(parsed);
                if (buffer.size() >= maxRowsPerPartition) {
                    writePartition(table, buffer, table.nextPartitionNumber++);
                    buffer.clear();
                }
            }

            if (!buffer.isEmpty())
                writePartition(table, buffer, table.nextPartitionNumber++);

        } catch (IOException e) {
            throw new RuntimeException("io error copying file", e);
        }

        long durationMs = System.currentTimeMillis() - startMs;

        int newPartitions = table.nextPartitionNumber - previousNextPartitionNumber;
        LOGGER.debug("statement=COPY table={} rowsWritten={} newPartitions={} version={} durationMs={}",
                tableIndex, totalRows, newPartitions, table.version, durationMs);
    }

    /**
     * Deletes rows using copy-on-write.
     * Active partitions that cannot contain a match are left untouched.
     * Candidate partitions are replaced by newly written partitions and the old metadata is marked as deprecated.
     * @param tableIndex the name of the table to delete from
     * @param predicate an optional predicate to filter rows to delete
     */
    public int deleteFromTable(Catalog catalog, int tableIndex, Optional<BoundStatement.Predicate> predicate) {
        markCatalog();

        requireNonNull(tableIndex);
        requireNonNull(predicate);

        long startMs = System.currentTimeMillis();

        TableMeta table = catalog.tables.get(tableIndex);

        int partitionsDeprecated = 0;
        List<Value[]> buffer = new ArrayList<>(maxRowsPerPartition);

        int rowsDeleted = 0;
        int previousNextPartitionNumber = table.nextPartitionNumber;
        int previousVersion = table.version;
        table.version++;

        for (PartitionMeta partition : activePartitions(catalog, tableIndex)) {
            boolean mayContain = predicate.isEmpty()
                    || partitionMayContain(
                            partition.stats.get(predicate.get().columnIndex()),
                            predicate.get().comparison(),
                            predicate.get().constant()
                        );

            if (!mayContain)
                continue;

            boolean anyDeleted = false;
            for (Value[] row : readPartitionRows(catalog, tableIndex, partition)) {
                boolean matches = predicate.isEmpty()
                        || predicate.get().matchesNonNull(row[predicate.get().columnIndex()]);

                if (matches) {
                    anyDeleted = true;
                    rowsDeleted++;
                    continue;
                }

                buffer.add(row);
                if (buffer.size() == maxRowsPerPartition) {
                    writePartition(table, buffer, table.nextPartitionNumber++);
                    buffer.clear();
                }
            }

            if (anyDeleted) {
                partition.markDeprecated(previousVersion);
                partitionsDeprecated++;
            }
        }

        if (rowsDeleted >= 1 && !buffer.isEmpty())
            writePartition(table, buffer, table.nextPartitionNumber++);

        int partitionsWritten = table.nextPartitionNumber - previousNextPartitionNumber;

        long durationMs = System.currentTimeMillis() - startMs;
        LOGGER.debug("statement=DELETE table={} rowsDeleted={} affectedPartitions={} newPartitions={} version={} durationMs={}",
                tableIndex, rowsDeleted, partitionsDeprecated, partitionsWritten, table.version, durationMs);
        return rowsDeleted;
    }

    /**
     * Reads all rows from one partition without applying a predicate.
     * Partition selection belongs to the planner; row filtering belongs to a FilterOperator.
     * @param tableIndex the index of the table
     * @param partition the partition metadata to read
     */
    public List<Value[]> readPartitionRows(Catalog catalog, int tableIndex, PartitionMeta partition) {
        requireNonNull(tableIndex);
        requireNonNull(partition);

        TableMeta table = catalog.tables.get(tableIndex);

        if (table == null)
            throw new IllegalArgumentException("unknown table: " + tableIndex);

        Path partFile = dataDir.resolve(partition.fileName);
        try (DataInputStream in = new DataInputStream(new FileInputStream(partFile.toFile()))) {
            int rows = in.readInt();
            int cols = table.columns.size();
            readPartitionStats(in, table.columns, partition);

            List<Value[]> colData = new ArrayList<>(cols);
            for (ColumnSpec column : table.columns)
                colData.add(readPartitionColumn(in, column.type(), rows));

            List<Value[]> result = new ArrayList<>(rows);
            for (int r = 0; r < rows; r++) {
                Value[] row = new Value[cols];
                for (int c = 0; c < cols; c++)
                    row[c] = colData.get(c)[r];
                result.add(row);
            }
            return result;
        } catch (IOException e) {
            throw new RuntimeException("failed reading partition " + partFile, e);
        }
    }

    /**
     * Returns the schema (list of columns) for the given table.
     * @param tableIndex the index of the table from `tableIndexOf`
     */
    public List<ColumnSpec> schema(Catalog catalog, int tableIndex) {
        if (tableIndex > catalog.tables.size())
            throw new IllegalArgumentException("invalid table index: " + tableIndex);

        return List.copyOf(catalog.tables.get(tableIndex).columns);
    }

    /**
     * Returns the table's partition metadata without reading partition data.
     * @param tableIndex the index of the table
     */
    public List<PartitionMeta> activePartitions(Catalog catalog, int tableIndex) {
        requireNonNull(tableIndex);

        TableMeta table = catalog.tables.get(tableIndex);
        if (table == null)
            throw new IllegalArgumentException("unknown table: " + tableIndex);

        return table.partitions.stream()
            .filter(partition -> partition.deprecatedSince == null)
            .toList();
    }

    /**
     * Returns the catalog for statement execution.
     * If the catalog file does not exist, it creates an empty catalog and returns that.
     */
    public Catalog openCatalog() {
        assert !catalogState.isOpen() : "a catalog is already open; close it before opening again";

        catalogState = CatalogState.OPEN_CLEAN;

        return readOrCreateEmptyCatalog();
    }

    /**
     * Marks the catalog as dirty, indicating that it has been modified and needs to be committed.
     * This method must be called before any modifications are made to the catalog.
     */
    private void markCatalog() {
        assert catalogState == CatalogState.OPEN_CLEAN : "catalog is not open or already dirty; cannot mark dirty";

        catalogState = CatalogState.OPEN_DIRTY;
    }

    /**
     * Closes the catalog, committing any changes if it was marked as dirty.
     */
    public void closeCatalog(Catalog catalog) {
        assert catalogState.isOpen() : "catalog is not open; cannot close";

        if (catalogState == CatalogState.OPEN_DIRTY)
            commitCatalog(catalog);

        catalogState = CatalogState.NONE;
    }

    public boolean isCatalogOpenForTesting() {
        return catalogState.isOpen();
    }

    public Catalog viewCatalogForTesting() {
        try {
            return readCatalog();
        } catch (FileNotFoundException e) {
            return new Catalog();
        }
    }

    // ---------- Helpers ----------

    static boolean partitionMayContain(ColumnStats stats, Comparison cmp, Value constant) {
        if (stats.min == null || stats.max == null)
            return true;

        return switch (cmp) {
            case EQUALS -> stats.min.compareAnyWithAny(constant) <= 0 && stats.max.compareAnyWithAny(constant) >= 0; // prune if pmin > constant or pmax < constant
            case LESS_THAN -> !(stats.min.compareAnyWithAny(constant) >= 0); // prune if pmin >= constant
            case GREATER_THAN -> !(stats.max.compareAnyWithAny(constant) <= 0); // prune if pmax <= constant
        };
    }

    public static sealed interface ParseCsvLineResult permits ParseCsvLineResult.Success, ParseCsvLineResult.Malformed {
        public static record Success(Value[] row) implements ParseCsvLineResult {}
        public static record Malformed(int charNumber, String description, Throwable cause) implements ParseCsvLineResult {
            public RuntimeException toRuntimeException(Path filePath, int lineNumber) {
                String message = "Malformed CSV in `" + filePath + "` at line " + lineNumber;
                if (charNumber >= 0)
                    message += ":" + charNumber;
                message += ": " + description;

                if (cause != null)
                    return new RuntimeException(message, cause);
                else
                    return new RuntimeException(message);
            }
        }

        public static ParseCsvLineResult success(Value[] row) { return new Success(row); }
        public static ParseCsvLineResult malformed(int charNumber, String description) { return new Malformed(charNumber, description, null); }
        public static ParseCsvLineResult malformed(int charNumber, String description, Throwable cause) { return new Malformed(charNumber, description, cause); }
    }

    static ParseCsvLineResult parseCsvLine(String line, List<ColumnSpec> cols) {
        String[] fields = line.split(",", -1);

        int charNumber = 0;
        if (fields.length != cols.size()) {
            for (int i = 0; i < Math.min(fields.length, cols.size()); i++)
                charNumber += fields[i].length() + 1; // +1 for the comma
            if (fields.length < cols.size())
                charNumber -= 1;
            return ParseCsvLineResult.malformed(charNumber, "Line has " + fields.length + " field(s) but expected " + cols.size());
        }

        Value[] parsed = new Value[fields.length];
        for (int i = 0; i < fields.length; i++) {
            String field = fields[i];
            ColumnType type = cols.get(i).type();
            try {
                parsed[i] = switch (type) {
                    case STRING -> new Value.String(field);
                    case LONG -> new Value.Long(Long.valueOf(field));
                    case DOUBLE -> new Value.Double(Double.valueOf(field));
                };
            } catch (NumberFormatException e) {
                return ParseCsvLineResult.malformed(charNumber, "Field `" + field + "` cannot be parsed as " + type, e);
            }
            charNumber += field.length() + 1; // +1 for the comma
        }
        return ParseCsvLineResult.success(parsed);
    }

    static void encodeValue(DataOutputStream out, Value value) throws IOException {
        switch (value) {
            case Value.String(String s) -> {
                byte[] bs = s.getBytes(StandardCharsets.US_ASCII);
                out.writeInt(bs.length);
                out.write(bs);
            }
            case Value.Long(long l) -> out.writeLong(l);
            case Value.Double(double d) -> out.writeDouble(d);
        }
    }

    static Value decodeValue(DataInputStream in, ColumnType t) throws IOException {
        return switch (t) {
            case STRING -> {
                int len = in.readInt();
                byte[] bs = new byte[len];
                in.readFully(bs);
                yield new Value.String(new String(bs, StandardCharsets.US_ASCII));
            }
            case LONG -> new Value.Long(in.readLong());
            case DOUBLE -> new Value.Double(in.readDouble());
        };
    }

    PartitionMeta writePartition(TableMeta table, List<Value[]> rows, int partitionIdx) {
        String name = partitionFileName(table.name, partitionIdx);

        Path path = dataDir.resolve(partitionFileName(table.name, partitionIdx));

        List<ColumnStats> stats = calcPartitionStats(rows, table.columns);
        PartitionMeta partition;

        try (DataOutputStream out = new DataOutputStream(new FileOutputStream(path.toFile()))) {
            int rowsCount = rows.size();
            int cols = table.columns.size();
            out.writeInt(rowsCount);

            // Store statistics in the partition header before the column data.
            for (int c = 0; c < cols; c++) {
                encodeValue(out, stats.get(c).min);
                encodeValue(out, stats.get(c).max);
            }

            // write column-wise: for each column write all row values
            for (int c = 0; c < cols; c++) {
                for (Value[] row : rows)
                    encodeValue(out, row[c]);
            }

            partition = new PartitionMeta(name, stats, rowsCount);
        } catch (IOException e) {
            throw new RuntimeException("failed writing partition", e);
        }

        for (int c = 0; c < table.columns.size(); c++) {
            String col = table.columns.get(c).name();
            LOGGER.debug("table={} partition={} column={} min={} max={}",
                    table.name, name, col, stats.get(c).min, stats.get(c).max);
        }

        table.partitions.add(partition);
        return partition;
    }

    static List<ColumnStats> calcPartitionStats(List<Value[]> rows, List<ColumnSpec> columns) {
        List<ColumnStats> stats = new ArrayList<>(columns.size());
        for (int column = 0; column < columns.size(); column++) {
            final int columnIdx = column;
            Stream<Value> values = rows.stream().map(row -> row[columnIdx]);

            ColumnStats s = switch (columns.get(column).type()) {
                case STRING -> calcColumnStats(values.map(Value::anyToString).iterator());
                case LONG -> calcColumnStats(values.map(Value::anyToLong).iterator());
                case DOUBLE -> calcColumnStats(values.map(Value::anyToDouble).iterator());
            };
            stats.add(s);
        }

        return stats;
    }

    private static <T extends Value.Comparable<T>> ColumnStats calcColumnStats(Iterator<T> values) {
        T min = null, max = null;

        if (values.hasNext()) {
            T value = values.next();
            min = value;
            max = value;
        }

        while (values.hasNext()) {
            T value = values.next();
            if (value.compareWith(min) < 0) min = value;
            if (value.compareWith(max) > 0) max = value;
        }

        return new ColumnStats(min, max);
    }

    private static void readPartitionStats(DataInputStream in, List<ColumnSpec> columns, PartitionMeta partition) throws IOException {
        partition.stats = new ArrayList<>(columns.size());
        for (ColumnSpec column : columns)
            partition.stats.add(new ColumnStats(decodeValue(in, column.type()), decodeValue(in, column.type())));
    }

    private static Value[] readPartitionColumn(DataInputStream in, ColumnType t, int rows) throws IOException {
        Value[] values = new Value[rows];
        for (int r = 0; r < rows; r++)
            values[r] = decodeValue(in, t);
        return values;
    }

    // ---------- Catalog Helpers ----------

    // Apparently, Windows can give us an error if we try to rename replacing an existing file while another process has it open.
    // So we use a lock file to ensure that readers finish before we replace the catalog file.
    private static boolean IS_WINDOWS = System.getProperty("os.name").toLowerCase().contains("win");

    private Catalog readOrCreateEmptyCatalog() {
        try {
            return readCatalog();
        } catch (FileNotFoundException e) {
            Catalog catalog = new Catalog();
            commitCatalog(catalog);
            return catalog;
        }
    }

    private Catalog readCatalog() throws FileNotFoundException {
        File catalogLockFile = catalogPath.resolveSibling(catalogLockFileName).toFile();

        if (!IS_WINDOWS)
            return readCatalogUnlocked();

        try (
            RandomAccessFile raf = new RandomAccessFile(catalogLockFile, "rw");
            FileChannel lockChannel = raf.getChannel();
            FileLock _ = lockChannel.lock(0, Long.MAX_VALUE, true)
        ) {
            return readCatalogUnlocked();
        } catch (FileNotFoundException e) {
            throw e;
        } catch (IOException e) {
            throw new RuntimeException("failed to lock catalog for reading", e);
        }
    }

    private Catalog readCatalogUnlocked() throws FileNotFoundException {
        try {
            return mapper.readValue(catalogPath.toFile(), Catalog.class);
        } catch (FileNotFoundException e) {
            throw e;
        } catch (IOException e) {
            throw new RuntimeException("failed to read catalog", e);
        }
    }

    private void commitCatalog(Catalog catalog) {
        File tempCatalogFile = catalogPath.resolveSibling(tempCatalogFileName()).toFile();

        try {
            mapper.writeValue(tempCatalogFile, catalog);
        } catch (IOException e) {
            throw new RuntimeException("failed to write catalog", e);
        }

        File catalogLockFile = catalogPath.resolveSibling(catalogLockFileName).toFile();

        if (!IS_WINDOWS) {
            renameReplaceTempCatalogUnlocked(tempCatalogFile.toPath());
            return;
        }

        try (
            RandomAccessFile raf = new RandomAccessFile(catalogLockFile, "rw");
            FileChannel lockChannel = raf.getChannel();
            FileLock _ = lockChannel.lock(0, Long.MAX_VALUE, false); // Blocks here until readers finish
        ) {
            renameReplaceTempCatalogUnlocked(tempCatalogFile.toPath());
        } catch (IOException e) {
            throw new RuntimeException("failed to lock catalog for writing", e);
        }
    }

    private void renameReplaceTempCatalogUnlocked(Path tempCatalogFilePath) {
        // TODO: For concurrency, we should check that our catalog changes won't conflict with other concurrent catalog changes.
        try {
            Files.move(tempCatalogFilePath, catalogPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new RuntimeException("failed to commit catalog", e);
        }
    }

    // ---------- Metadata Classes ----------

    public static final class Catalog {
        public List<TableMeta> tables = new ArrayList<>();

        public Catalog() {} // Needed for deserialization

        public Optional<Integer> tableIndexOf(String name) {
            Iterator<TableMeta> names = tables.iterator();
            for (int i = 0; names.hasNext(); i++) {
                if (names.next().name.equals(name))
                    return Optional.of(i);
            }

            return Optional.empty();
        }

        public Optional<TableMeta> tableByName(String name) {
            return tableIndexOf(name).map(i -> tables.get(i));
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Catalog that)) return false;
            return Objects.equals(tables, that.tables);
        }

        @Override
        public String toString() {
            return "Catalog{" +
                    "tables=" + tables +
                    '}';
        }
    }

    public static final class TableMeta {
        public final String name;
        public int version = 0;
        public int nextPartitionNumber = 0;
        public final List<ColumnSpec> columns = new ArrayList<>();
        public final List<PartitionMeta> partitions = new ArrayList<>();

        public TableMeta() { // Needed for deserialization
            this.name = null;
        }
        public TableMeta(String name, List<ColumnSpec> columns) {
            this.name = name;
            this.columns.addAll(columns);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof TableMeta that)) return false;
            return Objects.equals(columns, that.columns) &&
                   Objects.equals(partitions, that.partitions);
        }

        @Override
        public String toString() {
            return "TableMeta{" +
                    "version=" + version +
                    ", columns=" + columns +
                    ", partitions=" + partitions +
                    '}';
        }
    }

    public static final class PartitionMeta {
        public final String fileName;
        public List<ColumnStats> stats;
        public final int rowCount;
        public Integer deprecatedSince = null;

        public PartitionMeta() { // Needed for deserialization
            this.fileName = null;
            this.rowCount = 0;
        }
        public PartitionMeta(String fileName, List<ColumnStats> stats, int rowCount) {
            this.fileName = fileName;
            this.stats = stats;
            this.rowCount = rowCount;
        }

        public void markDeprecated(int version) {
            assert deprecatedSince == null;

            deprecatedSince = version;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof PartitionMeta that)) return false;
            return rowCount == that.rowCount &&
                   Objects.equals(fileName, that.fileName) &&
                   Objects.equals(stats, that.stats);
        }

        @Override
        public String toString() {
            return "PartitionMeta{" +
                    "fileName='" + fileName + "'" +
                    ", stats=" + stats +
                    ", rowCount=" + rowCount +
                    ", deprecatedSince=" + deprecatedSince +
                    "}";
        }
    }

    static record ColumnStats(Value min, Value max) {
        @Override
        public String toString() {
            return "ColumnStats[min=" + min + " max=" + max + "]";
        }
    }
}
