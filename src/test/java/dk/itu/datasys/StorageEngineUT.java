package dk.itu.datasys;

import static org.junit.jupiter.api.Assertions.*;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dk.itu.datasys.Spec.*;
import dk.itu.datasys.StorageEngine.*;

class StorageEngineUnitTest {

    @Test
    void encodeDecodeStringLongDouble(@TempDir Path tmp) throws IOException {
        Path filePath = tmp.resolve("test.bin");

        try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(filePath))) {
            StorageEngine.encodeValue(out, ColumnType.STRING, "hello");
            StorageEngine.encodeValue(out, ColumnType.LONG, 123L);
            StorageEngine.encodeValue(out, ColumnType.DOUBLE, 1.5);
        }

        try (DataInputStream in = new DataInputStream(Files.newInputStream(filePath))) {
            assertEquals("hello", (String) StorageEngine.decodeValue(in, ColumnType.STRING));
            assertEquals(123L, (Long) StorageEngine.decodeValue(in, ColumnType.LONG));
            assertEquals(1.5d, (Double) StorageEngine.decodeValue(in, ColumnType.DOUBLE));
        }
    }

    @Test
    void minMaxComputationAndPruning(@TempDir Path tmp) {
        StorageEngine eng = new StorageEngine(tmp);
        List<ColumnSpec> cols = List.of(new ColumnSpec("city", ColumnType.STRING), new ColumnSpec("distance", ColumnType.LONG));

        Object[] row1 = new Object[] {"A", 10L};
        Object[] row2 = new Object[] {"B", 20L};
        Object[] row3 = new Object[] {"C", 5L};

        eng.createTable("t", cols);
        StorageEngine.TableMeta tm = eng.catalogForTest().tables.get("t");

        // write partition
        eng.writePartition("t", tm, List.of(row1, row2, row3), 0);
        assertNotNull(tm);
        assertEquals(1, tm.partitions.size());

        // check min/max
        Object pmin = tm.partitions.get(0).mins.get(1);
        Object pmax = tm.partitions.get(0).maxs.get(1);
        assertEquals(5L, pmin);
        assertEquals(20L, pmax);

        // pruning decisions
        assertFalse(StorageEngine.partitionMayContain(pmin, pmax, Comparison.GREATER_THAN, 30L, ColumnType.LONG));
        assertTrue(StorageEngine.partitionMayContain(pmin, pmax, Comparison.GREATER_THAN, 15L, ColumnType.LONG));
        assertFalse(StorageEngine.partitionMayContain(pmin, pmax, Comparison.EQUALS, 4L, ColumnType.LONG));
    }

    @Test
    void csvParsingGoodAndBad() throws Exception {
        List<ColumnSpec> cols = List.of(new ColumnSpec("city", ColumnType.STRING), new ColumnSpec("distance", ColumnType.LONG));

        // valid csv
        Object[] parsed = StorageEngine.parseCsvLine("Copenhagen,12", cols);
        assertEquals("Copenhagen", parsed[0]);
        assertEquals(12L, parsed[1]);

        // malformed csv
        assertThrows(MalformedCsvException.class, () -> StorageEngine.parseCsvLine("too,many,fields", cols));
        assertThrows(MalformedCsvException.class, () -> StorageEngine.parseCsvLine("tooFewFields", cols));
        assertThrows(MalformedCsvException.class, () -> StorageEngine.parseCsvLine("Copenhagen,not a number", cols));
    }

    @Test
    void calculatePartitionStats() {
        List<ColumnSpec> cols = List.of(new ColumnSpec("city", ColumnType.STRING), new ColumnSpec("distance", ColumnType.LONG));

        // check partitions with data
        List<Object[]> rows = List.of(
            new Object[] {"A", 10L},
            new Object[] {"B", 20L},
            new Object[] {"C", 5L}
        );

        List<Object> mins = StorageEngine.partitionStats(rows, cols, StorageEngine.PartitionStatKind.MINIMUM);
        List<Object> maxs = StorageEngine.partitionStats(rows, cols, StorageEngine.PartitionStatKind.MAXIMUM);

        assertEquals("A", mins.get(0));
        assertEquals(5L, mins.get(1));
        assertEquals("C", maxs.get(0));
        assertEquals(20L, maxs.get(1));

        // empty partitions have null stats
        List<Object> empty = StorageEngine.partitionStats(List.of(), cols, StorageEngine.PartitionStatKind.MINIMUM);

        assertNull(empty.get(0));
        assertNull(empty.get(1));
    }
}
