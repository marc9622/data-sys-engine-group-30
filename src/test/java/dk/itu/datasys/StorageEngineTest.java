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

    private static Object[] parseCsvLineAssertSuccess(String line, List<ColumnSpec> columns) {
        ParseCsvLineResult result = StorageEngine.parseCsvLine(line, columns);
        return assertInstanceOf(ParseCsvLineResult.Success.class, result).row();
    }

    private static ParseCsvLineResult.Malformed parseCsvLineAssertMalformed(String line, List<ColumnSpec> columns) {
        ParseCsvLineResult result = StorageEngine.parseCsvLine(line, columns);
        return assertInstanceOf(ParseCsvLineResult.Malformed.class, result);
    }

    @Test
    void csvParsingGoodAndBad() throws Exception {
        List<ColumnSpec> cols = List.of(new ColumnSpec("city", ColumnType.STRING), new ColumnSpec("distance", ColumnType.LONG));

        // valid csv
        Object[] parsed = parseCsvLineAssertSuccess("Copenhagen,12", cols);
        assertEquals("Copenhagen", parsed[0]);
        assertEquals(12L, parsed[1]);

        // malformed csv
        ParseCsvLineResult.Malformed m1 = parseCsvLineAssertMalformed("too,many,fields", cols);
        assertEquals(9, m1.charNumber());
        assertEquals("Line has 3 field(s) but expected 2", m1.description());
        
        ParseCsvLineResult.Malformed m2 = parseCsvLineAssertMalformed("tooFewFields", cols);
        assertEquals(12, m2.charNumber());
        assertEquals("Line has 1 field(s) but expected 2", m2.description());

        ParseCsvLineResult.Malformed m3 = parseCsvLineAssertMalformed("Copenhagen,not a number", cols);
        assertEquals(11, m3.charNumber());
        assertEquals("Field `not a number` cannot be parsed as LONG", m3.description());
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
