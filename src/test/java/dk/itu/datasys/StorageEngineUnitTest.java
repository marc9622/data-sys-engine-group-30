package dk.itu.datasys;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

import dk.itu.datasys.Spec.ColumnSpec;
import dk.itu.datasys.Spec.ColumnType;

class StorageEngineUnitTest {

    @Test
    void encodeDecodeStringLongDouble() throws Exception {
        Path tmp = Files.createTempDirectory("engunit");
        StorageEngine eng = new StorageEngine(tmp);
        byte[] s = eng.encodeValue(ColumnType.STRING, "hello");
        byte[] l = eng.encodeValue(ColumnType.LONG, 123L);
        byte[] d = eng.encodeValue(ColumnType.DOUBLE, 1.5);

        assertEquals("hello", eng.decodeValue(ColumnType.STRING, s));
        assertEquals(123L, eng.decodeValue(ColumnType.LONG, l));
        assertEquals(1.5, (Double) eng.decodeValue(ColumnType.DOUBLE, d));
    }

    @Test
    void minMaxComputationAndPruning() throws Exception {
        Path tmp2 = Files.createTempDirectory("engunit2");
        StorageEngine eng = new StorageEngine(tmp2);
        List<ColumnSpec> cols = List.of(new ColumnSpec("city", ColumnType.STRING), new ColumnSpec("distance", ColumnType.LONG));
        Object[] row1 = new Object[] {"A", 10L};
        Object[] row2 = new Object[] {"B", 20L};
        Object[] row3 = new Object[] {"C", 5L};

        // reuse writePartition logic indirectly by creating a partition meta
        java.util.List<Object[]> list = List.of(row1, row2, row3);
        eng.createTable("t", cols);
        StorageEngine.TableMeta tm = eng.catalogForTest().tables.get("t");
        eng.writePartition("t", tm, list, 0);
        assertNotNull(tm);
        assertEquals(1, tm.partitions.size());
        Object pmin = tm.partitions.get(0).mins.get(1);
        Object pmax = tm.partitions.get(0).maxs.get(1);
        assertEquals(5L, pmin);
        assertEquals(20L, pmax);

        // pruning decisions
        assertFalse(eng.partitionMayContain(pmin, pmax, Spec.Comparison.GREATER_THAN, 30L, ColumnType.LONG));
        assertTrue(eng.partitionMayContain(pmin, pmax, Spec.Comparison.GREATER_THAN, 15L, ColumnType.LONG));
        assertFalse(eng.partitionMayContain(pmin, pmax, Spec.Comparison.EQUALS, 4L, ColumnType.LONG));
    }

    @Test
    void csvParsingGoodAndBad() throws Exception {
        Path tmp3 = Files.createTempDirectory("engunit3");
        StorageEngine eng = new StorageEngine(tmp3);
        List<ColumnSpec> cols = List.of(new ColumnSpec("city", ColumnType.STRING), new ColumnSpec("distance", ColumnType.LONG));
        Object[] parsed = eng.parseCsvLine("Copenhagen,12", cols, "f", 1);
        assertEquals("Copenhagen", parsed[0]);
        assertEquals(12L, parsed[1]);
        assertThrows(IllegalArgumentException.class, () -> eng.parseCsvLine("too,few,fields", cols, "f", 1));
        assertThrows(NumberFormatException.class, () -> eng.parseCsvLine("Copenhagen,not a number", cols, "f", 2));
    }
}
