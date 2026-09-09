package dk.itu.datasys;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dk.itu.datasys.Spec.*;

class StorageEngineIT {

    private static Path resource(String name) {
        return Path.of("src/test/resources/" + name).toAbsolutePath();
    }

    @Test
    void schemaPersistenceAndDuplicate(@TempDir Path tmp) {
        StorageEngine e1 = new StorageEngine(tmp);
        List<ColumnSpec> cols = List.of(new ColumnSpec("city", ColumnType.STRING));
        e1.createTable("t", cols);

        // new engine should see table and reject duplicate create
        StorageEngine e2 = new StorageEngine(tmp);
        assertThrows(IllegalArgumentException.class, () -> e2.createTable("t", cols));
    }

    @Test
    void roundTripAndTypes(@TempDir Path tmp) {
        System.setProperty("maxRowsPerPartition", "2");
        StorageEngine e = new StorageEngine(tmp);
        List<ColumnSpec> cols = List.of(new ColumnSpec("city", ColumnType.STRING), new ColumnSpec("distance", ColumnType.LONG), new ColumnSpec("price", ColumnType.DOUBLE));
        e.createTable("trips", cols);
        Path csv = resource("trips.csv");
        e.copyFromCsvFile("trips", csv.toString());

        List<Object[]> all = e.select("trips", "distance", Comparison.GREATER_THAN, -1L);
        assertEquals(8, all.size());
        for (Object[] r : all) {
            assertTrue(r[0] instanceof String);
            assertTrue(r[1] instanceof Long);
            assertTrue(r[2] instanceof Double);
        }
    }

    @Test
    void comparisonsAllTypes(@TempDir Path tmp) {
        System.setProperty("maxRowsPerPartition", "2");
        StorageEngine e = new StorageEngine(tmp);
        List<ColumnSpec> cols = List.of(new ColumnSpec("city", ColumnType.STRING), new ColumnSpec("distance", ColumnType.LONG), new ColumnSpec("price", ColumnType.DOUBLE));
        e.createTable("trips", cols);
        Path csv = resource("trips.csv");
        e.copyFromCsvFile("trips", csv.toString());

        // STRING equals
        List<Object[]> s = e.select("trips", "city", Comparison.EQUALS, "Copenhagen");
        assertEquals(3, s.size());

        // LONG greater
        List<Object[]> l = e.select("trips", "distance", Comparison.GREATER_THAN, 100L);
        assertEquals(4, l.size());

        // DOUBLE less
        List<Object[]> d = e.select("trips", "price", Comparison.LESS_THAN, 50.0);
        assertEquals(2, d.size());
    }

    @Test
    void emptyResultAndErrors(@TempDir Path tmp) {
        StorageEngine e = new StorageEngine(tmp);
        List<ColumnSpec> cols = List.of(new ColumnSpec("city", ColumnType.STRING), new ColumnSpec("distance", ColumnType.LONG), new ColumnSpec("price", ColumnType.DOUBLE));
        e.createTable("t", cols);
        Path csv = resource("trips.csv");
        e.copyFromCsvFile("t", csv.toString());

        List<Object[]> none = e.select("t", "distance", Comparison.GREATER_THAN, 1000L);
        assertEquals(0, none.size());

        assertThrows(IllegalArgumentException.class, () -> e.select("nope", "distance", Comparison.EQUALS, 1L));
        assertThrows(IllegalArgumentException.class, () -> e.select("t", "nope", Comparison.EQUALS, 1L));
        assertThrows(IllegalArgumentException.class, () -> e.select("t", "distance", Comparison.EQUALS, 1)); // Integer vs LONG
    }

    @Test
    void partitioningAndPersistence(@TempDir Path tmp) throws Exception {
        System.setProperty("maxRowsPerPartition", "2");
        StorageEngine e = new StorageEngine(tmp);
        List<ColumnSpec> cols = List.of(new ColumnSpec("city", ColumnType.STRING), new ColumnSpec("distance", ColumnType.LONG), new ColumnSpec("price", ColumnType.DOUBLE));
        e.createTable("trips", cols);
        Path csv = resource("trips.csv");
        e.copyFromCsvFile("trips", csv.toString());

        StorageEngine.TableMeta tm = e.catalogForTest().tables.get("trips");
        assertEquals(4, tm.partitions.size());
        // check distance min/max per partition (expected from original order)
        long[] expMin = new long[] {12, 95, 31, 88};
        long[] expMax = new long[] {187, 140, 210, 299};
        for (int i = 0; i < 4; i++) {
            assertEquals(expMin[i], tm.partitions.get(i).mins.get(1));
            assertEquals(expMax[i], tm.partitions.get(i).maxs.get(1));
        }

        // data persistence: new engine should read same partitions
        StorageEngine e2 = new StorageEngine(tmp);
        StorageEngine.TableMeta tm2 = e2.catalogForTest().tables.get("trips");
        assertEquals(4, tm2.partitions.size());
    }

    @Test
    void pruningWithSortedCsv(@TempDir Path tmp) {
        System.setProperty("maxRowsPerPartition", "2");
        List<ColumnSpec> cols = List.of(new ColumnSpec("city", ColumnType.STRING), new ColumnSpec("distance", ColumnType.LONG), new ColumnSpec("price", ColumnType.DOUBLE));

        StorageEngine e = new StorageEngine(tmp);

        e.createTable("trips", cols);
        e.copyFromCsvFile("trips", resource("trips_sorted.csv").toString());

        List<Object[]> res = e.select("trips", "distance", Comparison.GREATER_THAN, 200L);
        assertEquals(2, res.size());
        StorageEngine.ScanStats stats = e.getLastScanStats();
        assertTrue(stats.partitionsPruned() >= 2);
    }

    @Test
    void dataPersistenceAfterCopy(@TempDir Path tmp) {
        System.setProperty("maxRowsPerPartition", "2");
        List<ColumnSpec> cols = List.of(new ColumnSpec("city", ColumnType.STRING), new ColumnSpec("distance", ColumnType.LONG), new ColumnSpec("price", ColumnType.DOUBLE));

        // first engine
        StorageEngine e1 = new StorageEngine(tmp);

        e1.createTable("trips", cols);
        e1.copyFromCsvFile("trips", resource("trips.csv").toString());

        // second engine
        StorageEngine e2 = new StorageEngine(tmp);

        // assert that both engines return same results for a query
        List<Object[]> out1 = e1.select("trips", "distance", Comparison.GREATER_THAN, -1L);
        List<Object[]> out2 = e2.select("trips", "distance", Comparison.GREATER_THAN, -1L);
        assertEquals(out1.size(), out2.size());

        for (int i = 0; i < out1.size(); i++) {
            Object[] a = out1.get(i);
            Object[] b = out2.get(i);
            assertArrayEquals(a, b);
        }
    }
}
