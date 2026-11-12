package dk.itu.datasys;

import static dk.itu.datasys.TestingUtils.*;
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

class StorageEngineTest {
    private static void encode(DataOutputStream out, ColumnType type, Object value) throws IOException {
        StorageEngine.encodeValue(out, Value.fromObject(value, type));
    }

    private static void decodeAssert(String expected, DataInputStream in) throws IOException {
        assertValueEquals(expected, StorageEngine.decodeValue(in, ColumnType.STRING));
    }

    private static void decodeAssert(long expected, DataInputStream in) throws IOException {
        assertValueEquals(expected, StorageEngine.decodeValue(in, ColumnType.LONG));
    }

    private static void decodeAssert(double expected, DataInputStream in) throws IOException {
        assertValueEquals(expected, StorageEngine.decodeValue(in, ColumnType.DOUBLE));
    }

    @Test
    void encodeDecodeStringLongDouble(@TempDir Path tmp) throws IOException {
        Path filePath = tmp.resolve("test.bin");

        try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(filePath))) {
            encode(out, ColumnType.STRING, "hello");
            encode(out, ColumnType.LONG, 123L);
            encode(out, ColumnType.DOUBLE, 1.5);
        }

        try (DataInputStream in = new DataInputStream(Files.newInputStream(filePath))) {
            decodeAssert("hello", in);
            decodeAssert(123L, in);
            decodeAssert(1.5d, in);
        }
    }

    @Test
    void minMaxComputationAndPruning(@TempDir Path tmp) throws Exception {
        StorageEngine eng = new StorageEngine(tmp);
        List<ColumnSpec> cols = List.of(new ColumnSpec("city", ColumnType.STRING), new ColumnSpec("distance", ColumnType.LONG));

        Value[] row1 = Value.arrayFromObjects("A", 10L);
        Value[] row2 = Value.arrayFromObjects("B", 20L);
        Value[] row3 = Value.arrayFromObjects("C", 5L);

        createTableWithCatalog(eng, tripsName, cols);
        StorageEngine.TableMeta tm = eng.viewCatalogForTesting().tableByName(tripsName).get();

        // write partition
        eng.writePartition(tm, List.of(row1, row2, row3), 0);
        assertNotNull(tm);
        assertEquals(1, tm.partitions.size());

        // check min/max
        ColumnStats stats = tm.partitions.get(0).stats.get(1);
        assertValueEquals(5L, stats.min());
        assertValueEquals(20L, stats.max());

        // pruning decisions
        assertFalse(StorageEngine.partitionMayContain(stats, Comparison.GREATER_THAN, Value.of(30L)));
        assertTrue(StorageEngine.partitionMayContain(stats, Comparison.GREATER_THAN, Value.of(15L)));
        assertFalse(StorageEngine.partitionMayContain(stats, Comparison.EQUALS, Value.of(4L)));
    }

    private static Value[] parseCsvLineAssertSuccess(String line, List<ColumnSpec> columns) {
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
        Value[] parsed = parseCsvLineAssertSuccess("Copenhagen,12", cols);
        assertValueEquals("Copenhagen", parsed[0]);
        assertValueEquals(12L, parsed[1]);

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
        List<Value[]> rows = List.of(
            Value.arrayFromObjects("A", 10L),
            Value.arrayFromObjects("B", 20L),
            Value.arrayFromObjects("C", 5L)
        );

        List<ColumnStats> stats = StorageEngine.calcPartitionStats(rows, cols);

        assertValueEquals("A", stats.get(0).min());
        assertValueEquals(5L,  stats.get(1).min());
        assertValueEquals("C", stats.get(0).max());
        assertValueEquals(20L, stats.get(1).max());

        // empty partitions have null stats
        List<ColumnStats> empty = StorageEngine.calcPartitionStats(List.of(), cols);

        assertNull(empty.get(0).min());
        assertNull(empty.get(1).min());
        assertNull(empty.get(0).max());
        assertNull(empty.get(1).max());
    }
}
