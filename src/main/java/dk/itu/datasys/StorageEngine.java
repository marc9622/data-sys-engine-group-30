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
    private static final Object catalogCommitLock = new Object();
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
    private Map<String, Integer> catalogBaseVersions = Map.of();

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

        if (catalog.tables.containsKey(tableName))
            throw new IllegalArgumentException("table already exists: " + tableName);

        // check duplicate column names
        HashSet<String> names = new HashSet<>();
        for (ColumnSpec c : columns) {
            if (!names.add(c.name()))
                throw new IllegalArgumentException("duplicate column name: " + c.name());
        }

        TableMeta meta = new TableMeta(new ArrayList<>(columns));
        catalog.tables.put(tableName, meta);

        long durationMs = System.currentTimeMillis() - startMs;
        LOGGER.debug("created table table={} cols={} durationMs={}", tableName, columns.size(), durationMs);
    }

    /**
     * Copies the data from a CSV file into an empty table.
     * The CSV file must have the same number of columns as the table, and the column types must match.
     * @param tableName the name of the table to copy data into
     * @param csvFilePath the path to the CSV file
     */
    public void copyFromCsvFile(Catalog catalog, String tableName, Path csvFilePath) {
        markCatalog(); 

        requireNonNull(tableName);
        requireNonNull(csvFilePath);

        long startMs = System.currentTimeMillis();

        TableMeta table = catalog.tables.get(tableName);
        if (table == null)
            throw new IllegalArgumentException("unknown table: " + tableName);

        table.version++;

        if (!Files.exists(csvFilePath))
            throw new IllegalArgumentException("csv file not found: " + csvFilePath);

        int totalRows = 0;
        int previousNextPartitionNumber = table.nextPartitionNumber;

        try (BufferedReader r = Files.newBufferedReader(csvFilePath, StandardCharsets.US_ASCII)) {
            String line;
            List<Object[]> buffer = new ArrayList<>();
            while ((line = r.readLine()) != null) {
                totalRows++;

                Object[] parsed = switch (parseCsvLine(line, table.columns)) {
                    case ParseCsvLineResult.Success(Object[] row) -> row;
                    case ParseCsvLineResult.Malformed m ->
                        throw m.toRuntimeException(csvFilePath, totalRows);
                };

                buffer.add(parsed);
                if (buffer.size() >= maxRowsPerPartition) {
                    writePartition(tableName, table, buffer, table.nextPartitionNumber++);
                    buffer.clear();
                }
            }

            if (!buffer.isEmpty())
                writePartition(tableName, table, buffer, table.nextPartitionNumber++);

        } catch (IOException e) {
            throw new RuntimeException("io error copying file", e);
        }

        long durationMs = System.currentTimeMillis() - startMs;

        int newPartitions = table.nextPartitionNumber - previousNextPartitionNumber;
        LOGGER.debug("statement=COPY table={} rowsWritten={} newPartitions={} version={} durationMs={}",
                tableName, totalRows, newPartitions, table.version, durationMs);
    }

    /**
     * Deletes rows using copy-on-write.
     * Active partitions that cannot contain a match are left untouched.
     * Candidate partitions are replaced by newly written partitions and the old metadata is marked as deprecated.
     * @param tableName the name of the table to delete from
     * @param predicate an optional predicate to filter rows to delete
     */
    public int deleteFromTable(Catalog catalog, String tableName, Optional<Statement.Predicate> predicate) {
        markCatalog();

        requireNonNull(tableName);
        requireNonNull(predicate);

        long startMs = System.currentTimeMillis();

        TableMeta table = catalog.tables.get(tableName);
        if (table == null)
            throw new IllegalArgumentException("unknown table: " + tableName);

        List<ColumnSpec> schema = table.columns;
        int predicateColumn = predicate
                .map(p -> columnIndex(schema, p.columnName()))
                .orElse(-1);

        int partitionsDeprecated = 0;
        List<Object[]> buffer = new ArrayList<>(maxRowsPerPartition);

        int rowsDeleted = 0;
        int previousNextPartitionNumber = table.nextPartitionNumber;
        int previousVersion = table.version;
        table.version++;

        for (PartitionMeta partition : activePartitions(catalog, tableName)) {
            boolean mayContain = predicate.isEmpty()
                    || partitionMayContain(
                            partition.mins.get(predicateColumn),
                            partition.maxs.get(predicateColumn),
                            predicate.get().comparison(),
                            predicate.get().constant(),
                            schema.get(predicateColumn).type()
                        );

            if (!mayContain)
                continue;

            boolean anyDeleted = false;
            for (Object[] row : readPartitionRows(catalog, tableName, partition)) {
                boolean matches = predicate.isEmpty()
                        || predicate.get().matches(
                                row[predicateColumn],
                                schema.get(predicateColumn).type()
                            );

                if (matches) {
                    anyDeleted = true;
                    rowsDeleted++;
                    continue;
                }

                buffer.add(row);
                if (buffer.size() == maxRowsPerPartition) {
                    writePartition(tableName, table, buffer, table.nextPartitionNumber++);
                    buffer.clear();
                }
            }

            if (anyDeleted) {
                partition.markDeprecated(previousVersion);
                partitionsDeprecated++;
            }
        }

        if (rowsDeleted >= 1 && !buffer.isEmpty())
            writePartition(tableName, table, buffer, table.nextPartitionNumber++);

        int partitionsWritten = table.nextPartitionNumber - previousNextPartitionNumber;

        long durationMs = System.currentTimeMillis() - startMs;
        LOGGER.debug("statement=DELETE table={} rowsDeleted={} affectedPartitions={} newPartitions={} version={} durationMs={}",
                tableName, rowsDeleted, partitionsDeprecated, partitionsWritten, table.version, durationMs);
        return rowsDeleted;
    }

    private static int columnIndex(List<ColumnSpec> schema, String columnName) {
        for (int index = 0; index < schema.size(); index++) {
            if (schema.get(index).name().equals(columnName))
                return index;
        }
        throw new IllegalArgumentException("unknown column: " + columnName);
    }

    /**
     * Reads all rows from one partition without applying a predicate.
     * Partition selection belongs to the planner; row filtering belongs to a FilterOperator.
     * @param tableName the name of the table
     * @param partition the partition metadata to read
     */
    public List<Object[]> readPartitionRows(Catalog catalog, String tableName, PartitionMeta partition) {
        requireNonNull(tableName);
        requireNonNull(partition);

        TableMeta table = catalog.tables.get(tableName);

        if (table == null)
            throw new IllegalArgumentException("unknown table: " + tableName);

        Path partFile = dataDir.resolve(partition.fileName);
        try (DataInputStream in = new DataInputStream(new FileInputStream(partFile.toFile()))) {
            int rows = in.readInt();
            int cols = table.columns.size();
            readPartitionStats(in, table.columns, partition);

            List<Object[]> colData = new ArrayList<>(cols);
            for (ColumnSpec column : table.columns)
                colData.add(readPartitionColumn(in, column.type(), rows));

            List<Object[]> result = new ArrayList<>(rows);
            for (int r = 0; r < rows; r++) {
                Object[] row = new Object[cols];
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
     * @param tableName the name of the table
     */
    public List<ColumnSpec> schema(Catalog catalog, String tableName) {
        requireNonNull(tableName);

        TableMeta table = catalog.tables.get(tableName);
        if (table == null)
            throw new IllegalArgumentException("unknown table: " + tableName);

        return List.copyOf(table.columns);
    }

    /**
     * Returns the table's partition metadata without reading partition data.
     * @param tableName the name of the table
     */
    public List<PartitionMeta> activePartitions(Catalog catalog, String tableName) {
        requireNonNull(tableName);

        TableMeta table = catalog.tables.get(tableName);
        if (table == null)
            throw new IllegalArgumentException("unknown table: " + tableName);

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

        Catalog catalog = readOrCreateEmptyCatalog();
        catalogBaseVersions = new HashMap<>();
        for (Map.Entry<String, TableMeta> entry : catalog.tables.entrySet())
            catalogBaseVersions.put(entry.getKey(), entry.getValue().version);

        catalogState = CatalogState.OPEN_CLEAN;
        return catalog;
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

        try {
            if (catalogState == CatalogState.OPEN_DIRTY)
                commitCatalog(catalog);
        } finally {
            catalogBaseVersions = Map.of();
            catalogState = CatalogState.NONE;
        }
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

    static boolean partitionMayContain(Object pmin, Object pmax, Comparison cmp, Object constant, ColumnType type) {
        if (pmin == null || pmax == null)
            return true;

        int cmin = compareObjects(constant, pmin, type);
        int cmax = compareObjects(constant, pmax, type);

        return switch (cmp) {
            case EQUALS -> (cmin >= 0 && cmax <= 0);
            case LESS_THAN -> !(compareObjects(pmin, constant, type) >= 0); // prune if pmin >= constant
            case GREATER_THAN -> !(compareObjects(pmax, constant, type) <= 0); // prune if pmax <= constant
        };
    }

    private static int compareObjects(Object a, Object b, ColumnType type) {
        if (a == null && b == null) return 0;
        if (a == null) return -1;
        if (b == null) return 1;

        return switch (type) {
            case STRING -> ((String) a).compareTo((String) b);
            case LONG -> Long.compare(((Long) a), ((Long) b));
            case DOUBLE -> Double.compare(((Double) a), ((Double) b));
        };
    }

    public static sealed interface ParseCsvLineResult permits ParseCsvLineResult.Success, ParseCsvLineResult.Malformed {
        public static record Success(Object[] row) implements ParseCsvLineResult {}
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

        public static ParseCsvLineResult success(Object[] row) { return new Success(row); }
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

        Object[] parsed = new Object[fields.length];
        for (int i = 0; i < fields.length; i++) {
            String field = fields[i];
            ColumnType type = cols.get(i).type();
            try {
                switch (type) {
                    case STRING -> parsed[i] = field;
                    case LONG -> parsed[i] = Long.valueOf(field);
                    case DOUBLE -> parsed[i] = Double.valueOf(field);
                }
            } catch (NumberFormatException e) {
                return ParseCsvLineResult.malformed(charNumber, "Field `" + field + "` cannot be parsed as " + type, e);
            }
            charNumber += field.length() + 1; // +1 for the comma
        }
        return ParseCsvLineResult.success(parsed);
    }

    static void encodeValue(DataOutputStream out, ColumnType t, Object v) throws IOException {
        switch (t) {
            case STRING -> {
                byte[] bs = ((String) v).getBytes(StandardCharsets.US_ASCII);
                out.writeInt(bs.length);
                out.write(bs);
            }
            case LONG -> out.writeLong((Long) v);
            case DOUBLE -> out.writeDouble((Double) v);
        }
    }

    static Object decodeValue(DataInputStream in, ColumnType t) throws IOException {
        return switch (t) {
            case STRING -> {
                int len = in.readInt();
                byte[] bs = new byte[len];
                in.readFully(bs);
                yield new String(bs, StandardCharsets.US_ASCII);
            }
            case LONG -> in.readLong();
            case DOUBLE -> in.readDouble();
        };
    }

    PartitionMeta writePartition(String tableName, TableMeta table, List<Object[]> rows, int partitionIdx) {
        String name = partitionFileName(tableName, partitionIdx);
        PartitionMeta partition = writePartitionFile(tableName, table, rows, name);
        table.partitions.add(partition);
        return partition;
    }

    private PartitionMeta writePartitionFile(String tableName, TableMeta table, List<Object[]> rows, String fname) {
        Path out = dataDir.resolve(fname);
        try (DataOutputStream outp = new DataOutputStream(new FileOutputStream(out.toFile()))) {
            int rowsCount = rows.size();
            int cols = table.columns.size();
            outp.writeInt(rowsCount);

            List<Object> mins = partitionStats(rows, table.columns, PartitionStatKind.MINIMUM);
            List<Object> maxs = partitionStats(rows, table.columns, PartitionStatKind.MAXIMUM);

            // Store statistics in the partition header before the column data.
            for (int c = 0; c < cols; c++) {
                ColumnType t = table.columns.get(c).type();
                encodeValue(outp, t, mins.get(c));
                encodeValue(outp, t, maxs.get(c));
            }

            // write column-wise: for each column write all row values
            for (int c = 0; c < cols; c++) {
                ColumnType t = table.columns.get(c).type();
                writePartitionColumn(outp, t, rows, c);
            }

            PartitionMeta pm = new PartitionMeta(fname, mins, maxs, rowsCount);

            // log per-column min/max
            for (int c = 0; c < table.columns.size(); c++) {
                String col = table.columns.get(c).name();
                LOGGER.debug("table={} partition={} column={} min={} max={}", tableName, fname, col, mins.get(c), maxs.get(c));
            }

            return pm;
        } catch (IOException e) {
            throw new RuntimeException("failed writing partition", e);
        }
    }

    private static void writePartitionColumn(DataOutputStream outp, ColumnType t, List<Object[]> rows, int colIndex) throws IOException {
        for (Object[] row : rows) {
            Object val = row[colIndex];

            encodeValue(outp, t, val);
        }
    }

    static enum PartitionStatKind {
        MINIMUM,
        MAXIMUM;

        private int compare(Object a, Object b, ColumnType type) {
            int cmp = compareObjects(a, b, type);
            return this == MINIMUM ? cmp : -cmp;
        }
    }

    static List<Object> partitionStats(List<Object[]> rows, List<ColumnSpec> columns, PartitionStatKind statKind) {
        List<Object> stats = new ArrayList<>(Arrays.asList(new Object[columns.size()]));
        for (Object[] row : rows) {
            for (int c = 0; c < columns.size(); c++) {
                Object value = row[c];
                Object current = stats.get(c);
                ColumnType type = columns.get(c).type();
                if (current == null || statKind.compare(value, current, type) < 0) {
                    stats.set(c, value);
                }
            }
        }
        return stats;
    }

    private static void readPartitionStats(DataInputStream in, List<ColumnSpec> columns, PartitionMeta partition) throws IOException {
        partition.mins = new ArrayList<>(columns.size());
        partition.maxs = new ArrayList<>(columns.size());
        for (ColumnSpec column : columns) {
            partition.mins.add(decodeValue(in, column.type()));
            partition.maxs.add(decodeValue(in, column.type()));
        }
    }

    private static Object[] readPartitionColumn(DataInputStream in, ColumnType t, int rows) throws IOException {
        Object[] values = new Object[rows];
        for (int r = 0; r < rows; r++) {
            values[r] = decodeValue(in, t);
        }
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

        Catalog catalog;
        if (!IS_WINDOWS) {
            catalog = readCatalogUnlocked();
            normalizePartitionStats(catalog);
            return catalog;
        }

        try (
            RandomAccessFile raf = new RandomAccessFile(catalogLockFile, "rw");
            FileChannel lockChannel = raf.getChannel();
            FileLock _ = lockChannel.lock(0, Long.MAX_VALUE, true)
        ) {
            catalog = readCatalogUnlocked();
            normalizePartitionStats(catalog);
            return catalog;
        } catch (FileNotFoundException e) {
            throw e;
        } catch (IOException e) {
            throw new RuntimeException("failed to lock catalog for reading", e);
        }
    }

    /**
     * Normalizes the partition statistics to ensure that they are of the correct type. This is necessary because the statistics may be read from a JSON file, which does not preserve the original types of the values.
     */
    private static void normalizePartitionStats(Catalog catalog) {
        for (TableMeta table : catalog.tables.values()) {
            for (PartitionMeta partition : table.partitions) {
                for (int columnIndex = 0; columnIndex < table.columns.size(); columnIndex++) {
                    ColumnType type = table.columns.get(columnIndex).type();
                    partition.mins.set(columnIndex, normalizeStat(partition.mins.get(columnIndex), type));
                    partition.maxs.set(columnIndex, normalizeStat(partition.maxs.get(columnIndex), type));
                }
            }
        }
    }

    private static Object normalizeStat(Object value, ColumnType type) {
        if (value == null)
            return null;

        return switch (type) {
            case STRING -> value.toString();
            case LONG -> ((Number) value).longValue();
            case DOUBLE -> ((Number) value).doubleValue();
        };
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

        try {
            synchronized (catalogCommitLock) {
                if (!IS_WINDOWS) {
                    validateAndCommitCatalog(catalog, tempCatalogFile.toPath());
                    return;
                }

                File catalogLockFile = catalogPath.resolveSibling(catalogLockFileName).toFile();

                try (
                    RandomAccessFile raf = new RandomAccessFile(catalogLockFile, "rw");
                    FileChannel lockChannel = raf.getChannel();
                    FileLock _ = lockChannel.lock(0, Long.MAX_VALUE, false); // Blocks here until readers finish
                ) {
                    validateAndCommitCatalog(catalog, tempCatalogFile.toPath());
                } catch (IOException e) {
                    throw new RuntimeException("failed to lock catalog for writing", e);
                }
            }
        }
        finally {
            try {
                Files.deleteIfExists(tempCatalogFile.toPath());
            } catch (IOException e) {
                throw new RuntimeException("failed to delete temporary catalog", e);
            }
        }
    }

    private void validateAndCommitCatalog(Catalog catalog, Path tempCatalogFilePath) {
        Catalog currentCatalog;
        try {
            currentCatalog = readCatalogUnlocked();
        } catch (FileNotFoundException e) {
            currentCatalog = new Catalog();
        }

        // Validate every table that existed when this statement opened its snapshot.
        // Only tables modified by this statement need to be checked for a conflict.
        for (Map.Entry<String, Integer> base : catalogBaseVersions.entrySet()) {
            TableMeta currentTable = currentCatalog.tables.get(base.getKey());
            TableMeta candidateTable = catalog.tables.get(base.getKey());
            if (candidateTable != null && candidateTable.version != base.getValue())
                ensureTableVersionUnchanged(base.getKey(), base.getValue(), currentTable);
        }

        // A table missing from the base snapshot was created by this statement.
        // It conflicts if another writer created the same table in the meantime.
        for (String tableName : catalog.tables.keySet()) {
            if (!catalogBaseVersions.containsKey(tableName)) {
                TableMeta currentTable = currentCatalog.tables.get(tableName);
                if (currentTable != null) {
                    LOGGER.error("conflict table={} base={} current={}", tableName, 0, currentTable.version);
                    throw new ConflictException(tableName, 0, currentTable.version);
                }
            }
        }

        renameReplaceTempCatalogUnlocked(tempCatalogFilePath);
    }

    private void ensureTableVersionUnchanged(String tableName, int baseVersion, TableMeta currentTable) {
        int currentVersion = currentTable == null ? -1 : currentTable.version;
        if (currentVersion != baseVersion) {
            LOGGER.error("conflict table={} base={} current={}", tableName, baseVersion, currentVersion);
            throw new ConflictException(tableName, baseVersion, currentVersion);
        }
    }

    private void renameReplaceTempCatalogUnlocked(Path tempCatalogFilePath) {
        try {
            Files.move(tempCatalogFilePath, catalogPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new RuntimeException("failed to commit catalog", e);
        }
    }

    // ---------- Metadata Classes ----------

    public static final class Catalog {
        public Map<String, TableMeta> tables = new HashMap<>();

        public Catalog() {} // Needed for deserialization

        public boolean doesTableExist(String name) {
            return tables.containsKey(name);
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
        public int version = 0;
        public int nextPartitionNumber = 0;
        public final List<ColumnSpec> columns = new ArrayList<>();
        public final List<PartitionMeta> partitions = new ArrayList<>();

        public TableMeta() {} // Needed for deserialization
        public TableMeta(List<ColumnSpec> columns) {
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
        public List<Object> mins;
        public List<Object> maxs;
        public final int rowCount;
        public Integer deprecatedSince = null;

        public PartitionMeta() { // Needed for deserialization
            this.fileName = null;
            this.rowCount = 0;
        }
        public PartitionMeta(String fileName, List<Object> mins, List<Object> maxs, int rowCount) {
            this.fileName = fileName;
            this.mins = mins;
            this.maxs = maxs;
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
                   Objects.equals(mins, that.mins) &&
                   Objects.equals(maxs, that.maxs);
        }

        @Override
        public String toString() {
            return "PartitionMeta{" +
                    "fileName='" + fileName + '\'' +
                    ", mins=" + mins +
                    ", maxs=" + maxs +
                    ", rowCount=" + rowCount +
                    ", deprecatedSince=" + deprecatedSince +
                    '}';
        }
    }
}
