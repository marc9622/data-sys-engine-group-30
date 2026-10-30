package dk.itu.datasys;

import static dk.itu.datasys.TestingUtils.*;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dk.itu.datasys.Spec.*;
import dk.itu.datasys.StorageEngine.Catalog;

class StorageEngineIT {

    @Test
    void schemaPersistenceAndDuplicate(@TempDir Path tmp) {
        StorageEngine e1 = new StorageEngine(tmp);
        List<ColumnSpec> cols = tripsColumns.subList(0, 1);
        createTableWithCatalog(e1, "t", cols);

        // new engine should see table and reject duplicate create
        StorageEngine e2 = new StorageEngine(tmp);
        assertThrows(IllegalArgumentException.class, () -> createTableWithCatalog(e2, "t", cols));
    }

    @Test
    void roundTripAndTypes(@TempDir Path tmp) {
        System.setProperty("maxRowsPerPartition", "2");
        StorageEngine e = new StorageEngine(tmp);
        List<ColumnSpec> cols = tripsColumns;
        createTableWithCatalog(e, "trips", cols);
        Path csv = resource("trips.csv");
        copyFromCsvFileWithCatalog(e, "trips", csv);

        List<Object[]> all = new Executor(e).executeScript("SELECT * FROM trips WHERE distance > -1;").get(0);
        assertEquals(8, all.size());
        for (Object[] r : all) {
            assertInstanceOf(String.class, r[0]);
            assertInstanceOf(Long.class, r[1]);
            assertInstanceOf(Double.class, r[2]);
        }
    }

    @Test
    void comparisonsAllTypes(@TempDir Path tmp) {
        System.setProperty("maxRowsPerPartition", "2");
        StorageEngine e = new StorageEngine(tmp);
        List<ColumnSpec> cols = tripsColumns;
        createTableWithCatalog(e, "trips", cols);
        Path csv = resource("trips.csv");
        copyFromCsvFileWithCatalog(e, "trips", csv);

        Executor ex = new Executor(e);

        // STRING equals
        List<Object[]> s = ex.executeScript("SELECT * FROM trips WHERE city = 'Copenhagen';").get(0);
        assertEquals(3, s.size());

        // LONG greater
        List<Object[]> l = ex.executeScript("SELECT * FROM trips WHERE distance > 100;").get(0);
        assertEquals(4, l.size());

        // DOUBLE less
        List<Object[]> d = ex.executeScript("SELECT * FROM trips WHERE price < 50.0;").get(0);
        assertEquals(2, d.size());
    }

    @Test
    void projectionSelect(@TempDir Path tmp) {
        StorageEngine e = new StorageEngine(tmp);
        List<ColumnSpec> sourceColumns = tripsColumns;
        createTableWithCatalog(e, tripsName, sourceColumns);
        Path csv = resource("trips.csv");
        copyFromCsvFileWithCatalog(e, tripsName, csv);

        Object[] testedRow = tripsRows
            .stream()
            .filter(row -> row[0].equals("Odense"))
            .findAny()
            .get();

        List<List<Integer>> mappings = Streams
            .powerSetOf(IntStream.range(0, sourceColumns.size()).boxed().toList())
            .filter(set -> !set.isEmpty())
            .flatMap(subset -> Streams.permutationsOf(subset))
            .toList();

        Executor ex = new Executor(e);

        for (List<Integer> mapping : mappings) {
            List<String> projectionColumns = mapping.stream().map(sourceColumns::get).map(ColumnSpec::name).toList();
            String projectionString = String.join(", ", projectionColumns);

            List<Object[]> rows = ex.executeScript(
                    "SELECT " + projectionString +
                    " FROM " + tripsName +
                    " WHERE city = 'Odense';"
                    ).get(0);
            assertEquals(1, rows.size());

            Object[] expected = mapping.stream().map(i -> testedRow[i]).toArray();
            Object[] actual = rows.get(0);
            assertArrayEquals(expected, actual);
        }
    }

    @Test
    void limitedSelect(@TempDir Path tmp) {
        StorageEngine e = new StorageEngine(tmp);
        List<ColumnSpec> cols = tripsColumns;
        createTableWithCatalog(e, "trips", cols);
        Path csv = resource("trips.csv");
        copyFromCsvFileWithCatalog(e, "trips", csv);

        Executor ex = new Executor(e);

        for (int limit : List.of(0, 5, 10)) {
            List<Object[]> actualRows = ex.executeScript("SELECT * FROM trips WHERE distance > 20 LIMIT " + limit + ";").get(0);
            List<Object[]> expectedRows = tripsRows.stream().filter(r -> (Long) r[1] > 20).limit(limit).toList();

            assertEquals(expectedRows.size(), actualRows.size());
            for (int i = 0; i < expectedRows.size(); i++) {
                assertArrayEquals(expectedRows.get(i), actualRows.get(i));
            }
        }
    }

    @Test
    void emptyResultAndErrors(@TempDir Path tmp) {
        StorageEngine e = new StorageEngine(tmp);
        List<ColumnSpec> cols = List.of(new ColumnSpec("city", ColumnType.STRING), new ColumnSpec("distance", ColumnType.LONG), new ColumnSpec("price", ColumnType.DOUBLE));
        createTableWithCatalog(e, "t", cols);
        Path csv = resource("trips.csv");
        copyFromCsvFileWithCatalog(e, "t", csv);

        List<Object[]> none = new Executor(e).executeScript("SELECT * FROM t WHERE distance > 1000;").get(0);
        assertEquals(0, none.size());

        assertThrows(IllegalArgumentException.class, () ->
                new Executor(e).executeScript("SELECT * FROM nope WHERE distance = 1;"));
        assertThrows(IllegalArgumentException.class, () ->
                new Executor(e).executeScript("SELECT * FROM t WHERE nope = 1;"));
        assertThrows(IllegalArgumentException.class, () ->
                new Executor(e).executeScript("SELECT * FROM t WHERE distance = '1';"));
    }

    @Test
    void partitioningAndPersistence(@TempDir Path tmp) throws Exception {
        System.setProperty("maxRowsPerPartition", "2");
        StorageEngine e = new StorageEngine(tmp);
        List<ColumnSpec> cols = List.of(new ColumnSpec("city", ColumnType.STRING), new ColumnSpec("distance", ColumnType.LONG), new ColumnSpec("price", ColumnType.DOUBLE));
        createTableWithCatalog(e, "trips", cols);
        Path csv = resource("trips.csv");
        copyFromCsvFileWithCatalog(e, "trips", csv);

        StorageEngine.TableMeta tm = e.openCatalog().tables.get("trips");
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
        StorageEngine.TableMeta tm2 = e2.openCatalog().tables.get("trips");
        assertEquals(4, tm2.partitions.size());
    }

    @Test
    void pruningWithSortedCsv(@TempDir Path tmp) {
        System.setProperty("maxRowsPerPartition", "2");
        List<ColumnSpec> cols = List.of(new ColumnSpec("city", ColumnType.STRING), new ColumnSpec("distance", ColumnType.LONG), new ColumnSpec("price", ColumnType.DOUBLE));

        StorageEngine e = new StorageEngine(tmp);

        createTableWithCatalog(e, "trips", cols);
        copyFromCsvFileWithCatalog(e, "trips", resource("trips_sorted.csv"));

        Executor ex = new Executor(e);

        List<Object[]> res = ex.executeScript("SELECT * FROM trips WHERE distance > 200;").get(0);
        assertEquals(2, res.size());
        StorageEngine.ScanStats stats = ex.getLastScanStats();
        assertTrue(stats.partitionsPruned() >= 2);
    }

    @Test
    void dataPersistenceAfterCopy(@TempDir Path tmp) throws Exception {
        System.setProperty("maxRowsPerPartition", "2");
        List<ColumnSpec> cols = List.of(new ColumnSpec("city", ColumnType.STRING), new ColumnSpec("distance", ColumnType.LONG), new ColumnSpec("price", ColumnType.DOUBLE));

        // first engine
        StorageEngine e1 = new StorageEngine(tmp);

        createTableWithCatalog(e1, "trips", cols);
        copyFromCsvFileWithCatalog(e1, "trips", resource("trips.csv"));

        StorageEngine.Catalog c1 = e1.viewCatalogForTesting();

        // second engine
        StorageEngine e2 = new StorageEngine(tmp);

        StorageEngine.Catalog c2 = e2.viewCatalogForTesting();

        // assert that both engines return equal catalogs
        assertEquals(c1, c2);

        // assert that both engines return same results for a query
        List<Object[]> out1 = new Executor(e1).executeScript("SELECT * FROM trips WHERE distance > -1;").get(0);
        List<Object[]> out2 = new Executor(e2).executeScript("SELECT * FROM trips WHERE distance > -1;").get(0);
        assertEquals(out1.size(), out2.size());

        for (int i = 0; i < out1.size(); i++) {
            Object[] a = out1.get(i);
            Object[] b = out2.get(i);
            assertArrayEquals(a, b);
        }
    }

    @Test
    void catalogOpenClose(@TempDir Path tmp) throws Exception {
        StorageEngine e = new StorageEngine(tmp);
        Executor ex = new Executor(e);

        assertFalse(e.isCatalogOpenForTesting());

        ex.executeScript("CREATE TABLE t (city STRING, distance LONG, price DOUBLE);");

        assertFalse(e.isCatalogOpenForTesting());

        {
            Catalog catalog = e.openCatalog();
            assertTrue(e.isCatalogOpenForTesting());
            e.copyFromCsvFile(catalog, "t", resource("trips.csv"));
            assertTrue(e.isCatalogOpenForTesting());
            e.closeCatalog(catalog);
        }

        assertFalse(e.isCatalogOpenForTesting());

        {
            Catalog catalog = e.openCatalog();
            assertTrue(e.isCatalogOpenForTesting());
            ex.executeSelect(catalog, Statement.select("t").statement());
            assertTrue(e.isCatalogOpenForTesting());
            e.closeCatalog(catalog);
        }

        assertFalse(e.isCatalogOpenForTesting());
    }

    @Test
    void multipleCopies(@TempDir Path tmp) throws Exception {
        StorageEngine e = new StorageEngine(tmp);
        Executor ex = new Executor(e);

        List<List<Object[]>> results = ex.executeScript(
            "CREATE TABLE t (city STRING, distance LONG, price DOUBLE);" +
            "COPY t FROM '" + resource("trips.csv") + "';" +
            "COPY t FROM '" + resource("trips.csv") + "';" +
            "SELECT * FROM t WHERE distance > 100;");

        List<Object[]> actual = results.getLast();
        List<Object[]> expected = parseCsvFile(resource("trips.csv"))
            .stream()
            .filter(r -> (Long) r[1] > 100)
            .flatMap(r -> Stream.of(r, r))
            .toList();

        assertEquals(expected.size(), actual.size());
    }

    @Test
    void tableVersioning(@TempDir Path tmp) throws Exception {
        StorageEngine e = new StorageEngine(tmp);
        Executor ex = new Executor(e);

        int version = -1;

        ex.executeScript("CREATE TABLE t (city STRING, distance LONG, price DOUBLE);");
        assertTableVersion(++version, e, "t");

        ex.executeScript("COPY t FROM '" + resource("trips.csv") + "';");
        assertTableVersion(++version, e, "t");

        ex.executeScript("SELECT * FROM t WHERE distance > 100;");
        assertTableVersion(version, e, "t");

        ex.executeScript("COPY t FROM '" + resource("trips.csv") + "';");
        assertTableVersion(++version, e, "t");

        ex.executeScript("SELECT * FROM t WHERE distance > 100;");
        assertTableVersion(version, e, "t");
    }

    void assertTableVersion(int expected, StorageEngine engine, String tableName) {
        int actual = engine.viewCatalogForTesting().tables.get(tableName).version;
        assertEquals(expected, actual, "Expected table version " + expected + ", but got " + actual);
    }
}
