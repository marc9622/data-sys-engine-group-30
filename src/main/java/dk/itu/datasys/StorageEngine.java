package dk.itu.datasys;

import java.io.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static java.util.Objects.requireNonNull;

import com.fasterxml.jackson.annotation.JsonIgnore;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dk.itu.datasys.Spec.*;

public final class StorageEngine {
    private static final Logger LOGGER = LoggerFactory.getLogger(StorageEngine.class);

    public static record ScanStats(int partitionsTotal, int partitionsRead, int partitionsPruned) { }

    private final Path dataDir;
    private final Path catalogPath;
    private final Catalog catalog;
    private final ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private volatile ScanStats lastScanStats = new ScanStats(0, 0, 0);
    private final int maxRowsPerPartition;

    /**
     * @param dataDir the directory where all persistent state (catalog + data files) lives under
     */
    public StorageEngine(Path dataDir) {
        requireNonNull(dataDir, "dataDir");

        this.dataDir = dataDir;
        this.catalogPath = dataDir.resolve("catalog.json");
        this.maxRowsPerPartition = Integer.getInteger("maxRowsPerPartition", 1000);

        try {
            Files.createDirectories(dataDir);
        } catch (IOException e) {
            throw new RuntimeException("cannot create data dir: " + dataDir, e);
        }

        if (Files.exists(catalogPath)) {
            try {
                this.catalog = mapper.readValue(catalogPath.toFile(), Catalog.class);

                for (TableMeta table : catalog.tables.values()) {
                    for (PartitionMeta partition : table.partitions) {
                        loadPartitionStats(partition, table.columns);
                    }
                }
            } catch (Exception e) {
                throw new RuntimeException("failed to read catalog", e);
            }
        } else {
            this.catalog = new Catalog();
            persistCatalog();
        }
    }

    public ScanStats getLastScanStats() {
        return lastScanStats;
    }

    /**
     * Ensures that the catalog contains a table with the given name and columns.
     * @param tableName the name of the table to create
     * @param columns the list of columns for the table
     */
    public void createTable(String tableName, List<ColumnSpec> columns) {
        requireNonNull(tableName, "tableName");
        requireNonNull(columns, "columns");

        if (columns.isEmpty())
            throw new IllegalArgumentException("empty column list");

        synchronized (catalog) {
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
            persistCatalog();
            LOGGER.debug("table={} cols={} ", tableName, columns.size());
        }
    }

    /**
     * Copies the data from a CSV file into an empty table. The CSV file must have the same number of columns as the table, and the column types must match.
     * @param tableName the name of the table to copy data into
     * @param csvFilePath the path to the CSV file
     */
    public void copyFromCsvFile(String tableName, String csvFilePath) {
        requireNonNull(tableName);
        requireNonNull(csvFilePath);

        TableMeta table;
        synchronized (catalog) {
            table = catalog.tables.get(tableName);
            if (table == null)
                throw new IllegalArgumentException("unknown table: " + tableName);
            if (table.hasData)
                throw new UnsupportedOperationException("table already has data: " + tableName);
            table.hasData = true; // reserve
            persistCatalog();
        }

        Path csv = Path.of(csvFilePath);
        if (!Files.exists(csv))
            throw new IllegalArgumentException("csv file not found: " + csvFilePath);

        long start = System.currentTimeMillis();
        int totalRows = 0;
        int partitions = 0;

        try (BufferedReader r = Files.newBufferedReader(csv, StandardCharsets.US_ASCII)) {
            String line;
            List<Object[]> buffer = new ArrayList<>();
            while ((line = r.readLine()) != null) {
                totalRows++;

                Object[] parsed;
                try {
                    parsed = parseCsvLine(line, table.columns);
                } catch (MalformedCsvException e) {
                    throw e.toRuntimeException(csvFilePath, totalRows);
                }

                buffer.add(parsed);
                if (buffer.size() >= maxRowsPerPartition) {
                    writePartition(tableName, table, buffer, partitions);
                    partitions++;
                    buffer.clear();
                }
            }

            if (!buffer.isEmpty()) {
                writePartition(tableName, table, buffer, partitions);
                partitions++;
            }

            long dur = System.currentTimeMillis() - start;
            synchronized (catalog) {
                persistCatalog();
            }
            LOGGER.debug("table={} file={} rows={} partitions={} durationMs={}", tableName, csvFilePath, totalRows, partitions, dur);

        } catch (IOException e) {
            throw new RuntimeException("io error copying file", e);
        }
    }

    /**
     * Retrieves rows from a table where the values in the specified columns satisfy the given comparison with the provided constant.
     * @param tableName the name of the table to query
     * @param columnName the name of the column to compare
     * @param comparison the type of comparison to perform
     * @param constant the constant value to compare against
     */
    public List<Object[]> select(String tableName, String columnName, Comparison comparison, Object constant) {
        requireNonNull(tableName);
        requireNonNull(columnName);
        requireNonNull(comparison);
        requireNonNull(constant);

        TableMeta table;
        synchronized (catalog) {
            table = catalog.tables.get(tableName);
            if (table == null)
                throw new IllegalArgumentException("unknown table: " + tableName);
        }

        int colIdx = -1;
        for (int i = 0; i < table.columns.size(); i++) {
            if (table.columns.get(i).name().equals(columnName)) {
                colIdx = i;
                break;
            }
        }
        if (colIdx == -1)
            throw new IllegalArgumentException("unknown column: " + columnName);

        ColumnType colType = table.columns.get(colIdx).type();
        // exact type match
        if (!typeMatches(colType, constant))
            throw new IllegalArgumentException("constant type does not match column type");

        int partitionsTotal = table.partitions.size();
        int partitionsRead = 0;
        int partitionsPruned = 0;
        List<Object[]> out = new ArrayList<>();

        long start = System.currentTimeMillis();
        for (PartitionMeta partition : table.partitions) {
            Object pmin = partition.mins.get(colIdx);
            Object pmax = partition.maxs.get(colIdx);

            boolean canContain = partitionMayContain(pmin, pmax, comparison, constant, colType);
            LOGGER.debug("table={} column={} comparison={} const={} partition={} min={} max={} decision={}", tableName, columnName, comparison, constant, partitionsRead, pmin, pmax, canContain ? "READ" : "PRUNED");

            if (!canContain) {
                partitionsPruned++;
                continue;
            }

            readPartition(table, partition, comparison, constant, colIdx, colType, out);
            partitionsRead++;
        }

        long dur = System.currentTimeMillis() - start;
        lastScanStats = new ScanStats(partitionsTotal, partitionsRead, partitionsPruned);
        LOGGER.debug("table={} column={} comparison={} const={} partitionsRead={} partitionsPruned={} rowsOut={} durationMs={}", tableName, columnName, comparison, constant, partitionsRead, partitionsPruned, out.size(), dur);

        return out;
    }

    public void clearAllData() {
        synchronized (catalog) {
            for (TableMeta table : catalog.tables.values()) {
                for (PartitionMeta partition : table.partitions) {
                    Path partFile = dataDir.resolve(partition.fileName);
                    try {
                        Files.deleteIfExists(partFile);
                    } catch (IOException e) {
                        throw new RuntimeException("failed to delete partition file: " + partFile, e);
                    }
                }
            }
            catalog.tables.clear();
            persistCatalog();
        }
    }

    // ---------- Helpers ----------

    private static boolean typeMatches(ColumnType t, Object constant) {
        return switch (t) {
            case STRING -> constant instanceof String;
            case LONG -> constant instanceof Long;
            case DOUBLE -> constant instanceof Double;
        };
    }

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

    private static boolean rowMatches(Object v, Comparison cmp, Object constant, ColumnType type) {
        int c = compareObjects(v, constant, type);
        return switch (cmp) {
            case EQUALS -> c == 0;
            case LESS_THAN -> c < 0;
            case GREATER_THAN -> c > 0;
        };
    }

    public static final class MalformedCsvException extends Exception {
        public MalformedCsvException(String message) {
            super(message);
        }

        public MalformedCsvException(Throwable cause) {
            super(cause);
        }

        public RuntimeException toRuntimeException(String fileName, int lineNumber) {
            return new RuntimeException("Malformed CSV " + fileName + " at line " + lineNumber + ": " + getMessage(), this);
        }
    }

    static Object[] parseCsvLine(String line, List<ColumnSpec> cols) throws MalformedCsvException {
        String[] fields = line.split(",", -1);
        if (fields.length != cols.size())
            throw new MalformedCsvException("field count");

        Object[] parsed = new Object[fields.length];
        for (int i = 0; i < fields.length; i++) {
            String s = fields[i];
            ColumnType t = cols.get(i).type();
            try {
                switch (t) {
                    case STRING -> parsed[i] = s;
                    case LONG -> parsed[i] = Long.valueOf(s);
                    case DOUBLE -> parsed[i] = Double.valueOf(s);
                    default -> throw new MalformedCsvException("unknown type");
                }
            } catch (Exception ex) {
                throw new MalformedCsvException(ex);
            }
        }
        return parsed;
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

    void writePartition(String tableName, TableMeta table, List<Object[]> rows, int partitionIdx) {
        String fname = tableName + "-part-" + partitionIdx + ".bin";
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

            // record partition meta
            PartitionMeta pm = new PartitionMeta(fname, mins, maxs, rowsCount);
            table.partitions.add(pm);

            // log per-column min/max
            for (int c = 0; c < table.columns.size(); c++) {
                String col = table.columns.get(c).name();
                LOGGER.debug("table={} partition={} column={} min={} max={}", tableName, partitionIdx, col, mins.get(c), maxs.get(c));
            }

        } catch (IOException e) {
            throw new RuntimeException("failed writing partition", e);
        }
    }

    static void writePartitionColumn(DataOutputStream outp, ColumnType t, List<Object[]> rows, int colIndex) throws IOException {
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

    private void loadPartitionStats(PartitionMeta partition, List<ColumnSpec> columns) throws IOException {
        Path partFile = dataDir.resolve(partition.fileName);
        try (DataInputStream in = new DataInputStream(new FileInputStream(partFile.toFile()))) {
            int rows = in.readInt();
            if (rows != partition.rowCount)
                throw new IOException("partition header does not match catalog: " + partFile);
            readPartitionStats(in, columns, partition);
        }
    }

    void readPartition(TableMeta table, PartitionMeta partition, Comparison comparison, Object constant, int colIdx, ColumnType colType, List<Object[]> out) {
        Path partFile = dataDir.resolve(partition.fileName);
        try (DataInputStream in = new DataInputStream(new FileInputStream(partFile.toFile()))) {
            int rows = in.readInt();
            int cols = table.columns.size();
            readPartitionStats(in, table.columns, partition);

            // load column-wise: for each column read all row values into an array
            List<Object[]> colData = new ArrayList<>(cols);
            for (int c = 0; c < cols; c++) {
                ColumnType t = table.columns.get(c).type();
                colData.add(readPartitionColumn(in, t, rows));
            }

            // assemble rows from column arrays and evaluate predicate
            for (int r = 0; r < rows; r++) {
                Object[] row = new Object[cols];
                for (int c = 0; c < cols; c++) row[c] = colData.get(c)[r];
                Object v = row[colIdx];
                if (rowMatches(v, comparison, constant, colType)) out.add(row);
            }
        } catch (IOException e) {
            throw new RuntimeException("failed reading partition " + partFile, e);
        }
    }

    static Object[] readPartitionColumn(DataInputStream in, ColumnType t, int rows) throws IOException {
        Object[] values = new Object[rows];
        for (int r = 0; r < rows; r++) {
            values[r] = decodeValue(in, t);
        }
        return values;
    }

    Catalog catalogForTest() { return catalog; }

    private void persistCatalog() {
        try {
            mapper.writeValue(catalogPath.toFile(), catalog);
        } catch (IOException e) {
            throw new RuntimeException("failed to write catalog", e);
        }
    }

    // ---------- Metadata Classes ----------

    public static final class Catalog {
        public Map<String, TableMeta> tables = new HashMap<>();
        public Catalog() {}

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Catalog that)) return false;
            return Objects.equals(tables, that.tables);
        }
    }

    public static final class TableMeta {
        public List<ColumnSpec> columns = new ArrayList<>();
        public List<PartitionMeta> partitions = new ArrayList<>();
        private boolean hasData = false;

        public TableMeta() {}
        public TableMeta(List<ColumnSpec> columns) {
            this.columns = columns;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof TableMeta that)) return false;
            return Objects.equals(columns, that.columns) &&
                   Objects.equals(partitions, that.partitions);
        }
    }

    public static final class PartitionMeta {
        public String fileName;
        @JsonIgnore
        public List<Object> mins;
        @JsonIgnore
        public List<Object> maxs;
        public int rowCount;

        public PartitionMeta() {}
        public PartitionMeta(String fileName, List<Object> mins, List<Object> maxs, int rowCount) {
            this.fileName = fileName;
            this.mins = mins;
            this.maxs = maxs;
            this.rowCount = rowCount;
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
    }

}
