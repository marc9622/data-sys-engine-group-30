package dk.itu.datasys;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dk.itu.datasys.Spec.*;
import dk.itu.datasys.StorageEngine.MalformedCsvException;

class StorageEngineUnitTest {

    @Test
    void encodeDecodeStringLongDouble(@TempDir Path tmp) throws Exception {
        StorageEngine eng = new StorageEngine(tmp);

        byte[] s = eng.encodeValue(ColumnType.STRING, "hello");
        byte[] l = eng.encodeValue(ColumnType.LONG, 123L);
        byte[] d = eng.encodeValue(ColumnType.DOUBLE, 1.5);

        assertEquals("hello", (String) eng.decodeValue(ColumnType.STRING, s));
        assertEquals(123L, (Long) eng.decodeValue(ColumnType.LONG, l));
        assertEquals(1.5d, (Double) eng.decodeValue(ColumnType.DOUBLE, d));
    }

    @Test
    void minMaxComputationAndPruning(@TempDir Path tmp) throws Exception {
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
        assertFalse(eng.partitionMayContain(pmin, pmax, Comparison.GREATER_THAN, 30L, ColumnType.LONG));
        assertTrue(eng.partitionMayContain(pmin, pmax, Comparison.GREATER_THAN, 15L, ColumnType.LONG));
        assertFalse(eng.partitionMayContain(pmin, pmax, Comparison.EQUALS, 4L, ColumnType.LONG));
    }

    @Test
    void csvParsingGoodAndBad(@TempDir Path tmp) throws Exception {
        StorageEngine eng = new StorageEngine(tmp);
        List<ColumnSpec> cols = List.of(new ColumnSpec("city", ColumnType.STRING), new ColumnSpec("distance", ColumnType.LONG));

        // valid csv
        Object[] parsed = eng.parseCsvLine("Copenhagen,12", cols);
        assertEquals("Copenhagen", parsed[0]);
        assertEquals(12L, parsed[1]);

        // malformed csv
        assertThrows(MalformedCsvException.class, () -> eng.parseCsvLine("too,many,fields", cols));
        assertThrows(MalformedCsvException.class, () -> eng.parseCsvLine("tooFewFields", cols));
        assertThrows(MalformedCsvException.class, () -> eng.parseCsvLine("Copenhagen,not a number", cols));
    }
}
