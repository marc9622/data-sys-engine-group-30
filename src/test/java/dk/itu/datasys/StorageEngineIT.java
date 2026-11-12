package dk.itu.datasys;

import static dk.itu.datasys.TestingUtils.*;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dk.itu.datasys.Spec.*;
import dk.itu.datasys.StorageEngine.Catalog;
import dk.itu.datasys.StorageEngine.ColumnStats;
import dk.itu.datasys.TestingUtils.Streams;

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

        Value[] testedRow = tripsRows
            .stream()
            .filter(row -> row[0].toObject().equals("Odense"))
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

            Value[] expected = mapping.stream().map(i -> testedRow[i]).toArray(Value[]::new);
            Value[] actual = Value.arrayFromObjects(rows.get(0));
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
            List<Value[]> expectedRows = tripsRows.stream().filter(r -> r[1].anyToLong().value() > 20).limit(limit).toList();

            assertEquals(expectedRows.size(), actualRows.size());
            for (int i = 0; i < expectedRows.size(); i++) {
                Value[] actual = Value.arrayFromObjects(actualRows.get(i));
                assertArrayEquals(expectedRows.get(i), actual);
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

        StorageEngine.TableMeta tm = e.openCatalog().tableByName("trips").get();
        assertEquals(4, tm.partitions.size());
        // check distance min/max per partition (expected from original order)
        long[] expMin = new long[] {12, 95, 31, 88};
        long[] expMax = new long[] {187, 140, 210, 299};
        for (int i = 0; i < 4; i++) {
            ColumnStats stats = tm.partitions.get(i).stats.get(1);
            assertValueEquals(expMin[i], stats.min());
            assertValueEquals(expMax[i], stats.max());
        }

        // data persistence: new engine should read same partitions
        StorageEngine e2 = new StorageEngine(tmp);
        StorageEngine.TableMeta tm2 = e2.openCatalog().tableByName("trips").get();
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

        assertEquals(c1, c2);

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
            e.copyFromCsvFile(catalog, 0, resource("trips.csv"));
            assertTrue(e.isCatalogOpenForTesting());
            e.closeCatalog(catalog);
        }

        assertFalse(e.isCatalogOpenForTesting());

        {
            Catalog catalog = e.openCatalog();
            assertTrue(e.isCatalogOpenForTesting());
            ex.executeSelect(catalog, BoundStatement.select(0).statement());
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
        List<Value[]> expected = parseCsvFile(resource("trips.csv"))
            .stream()
            .filter(r -> r[1].anyToLong().value() > 100)
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

    @Test
    void deleteCommitsVersionAndSelectSeesEachCommit(@TempDir Path tmp) {
        StorageEngine e = new StorageEngine(tmp);
        Executor ex = new Executor(e);

        int tripsSize = parseCsvFile(resource("trips.csv")).size();

        int amountCopenhagenInTrips = (int) parseCsvFile(resource("trips.csv")).stream()
            .filter(r -> r[0].toObject().equals("Copenhagen"))
            .count();

        int version = -1;

        ex.executeScript("CREATE TABLE t (city STRING, distance LONG, price DOUBLE);");
        assertTableVersion(++version, e, "t");
        
        ex.executeScript("COPY t FROM '" + resource("trips.csv") + "';");
        assertTableVersion(++version, e, "t");
        assertEquals(tripsSize, ex.executeScript("SELECT * FROM t;").getFirst().size());
        
        int newTripsSize = tripsSize * 2;
        int newAmountCopenhagenInTrips = amountCopenhagenInTrips * 2;

        ex.executeScript("COPY t FROM '" + resource("trips.csv") + "';");
        assertTableVersion(++version, e, "t");
        assertEquals(newTripsSize, ex.executeScript("SELECT * FROM t;").getFirst().size());
        
        ex.executeScript("DELETE FROM t WHERE city = 'Copenhagen';");
        assertTableVersion(++version, e, "t");
        assertEquals(newTripsSize - newAmountCopenhagenInTrips, ex.executeScript("SELECT * FROM t;").getFirst().size());

        ex.executeScript("DELETE FROM t;");
        assertTableVersion(++version, e, "t");
        assertEquals(0, ex.executeScript("SELECT * FROM t;").getFirst().size());
    }

    @Test
    void deleteRewriteSetOnlyRewritesMatchingPartitions(@TempDir Path tmp) {
        System.setProperty("maxRowsPerPartition", "2");
        StorageEngine e = new StorageEngine(tmp);
        Executor ex = new Executor(e);

        ex.executeScript("CREATE TABLE trips (city STRING, distance LONG, price DOUBLE);");
        ex.executeScript("COPY trips FROM '" + resource("trips_sorted.csv") + "';");

        List<String> originalFiles = e.viewCatalogForTesting().tableByName("trips").get().partitions.stream()
                .map(partition -> partition.fileName)
                .toList();
        assertEquals(IntStream.range(0, 4).mapToObj(this::tripsPartitionName).toList(), originalFiles);

        ex.executeScript("DELETE FROM trips WHERE distance > 150;");

        StorageEngine.TableMeta table = e.viewCatalogForTesting().tableByName("trips").get();

        // partions 0 and 1 are pruned, 2 and 3 are rewritten.
        assertEquals(2, table.version);
        assertNull(table.partitions.get(0).deprecatedSince);
        assertNull(table.partitions.get(1).deprecatedSince);
        assertEquals(1, table.partitions.get(2).deprecatedSince);
        assertEquals(1, table.partitions.get(3).deprecatedSince);

        // The originals files still exist and only the non-empty rewrite is added.
        assertEquals(5, table.partitions.size());
        assertEquals(originalFiles.get(0), table.partitions.get(0).fileName);
        assertEquals(originalFiles.get(1), table.partitions.get(1).fileName);
        assertEquals(originalFiles.get(2), table.partitions.get(2).fileName);
        assertEquals(originalFiles.get(3), table.partitions.get(3).fileName);
        assertEquals(tripsPartitionName(4), table.partitions.get(4).fileName);

        List<Object[]> rows = ex.executeScript("SELECT * FROM trips;").getFirst();
        assertEquals(5, rows.size());
        assertTrue(rows.stream().allMatch(row -> (Long) row[1] <= 150));
    }

    @Test
    void freshEngineSeesPostDeleteVersion(@TempDir Path tmp) {
        StorageEngine first = new StorageEngine(tmp);
        Executor firstExecutor = new Executor(first);

        firstExecutor.executeScript("CREATE TABLE trips (city STRING, distance LONG, price DOUBLE);");
        firstExecutor.executeScript("COPY trips FROM '" + resource("trips.csv") + "';");
        firstExecutor.executeScript("DELETE FROM trips WHERE distance > 200;");
        int expectedRows = parseCsvFile(resource("trips.csv")).stream().filter(r -> r[1].anyToLong().value() <= 200).toList().size();

        assertTableVersion(2, first, "trips");

        StorageEngine fresh = new StorageEngine(tmp);
        assertTableVersion(2, fresh, "trips");

        List<Object[]> rows = new Executor(fresh).executeScript("SELECT * FROM trips;").getFirst();
        assertEquals(expectedRows, rows.size());
        assertTrue(rows.stream().allMatch(row -> (Long) row[1] <= 200));
    }

    @Test
    void deleteDoesNotCreateEmptyReplacementPartition(@TempDir Path tmp) {
        System.setProperty("maxRowsPerPartition", "100");
        StorageEngine e = new StorageEngine(tmp);
        Executor ex = new Executor(e);

        ex.executeScript("CREATE TABLE trips (city STRING, distance LONG, price DOUBLE);");
        ex.executeScript("COPY trips FROM '" + resource("trips.csv") + "';");

        // This should not match any rows; keep current partition
        ex.executeScript("DELETE FROM trips WHERE city = 'Berlin';");

        Catalog catalog1 = e.viewCatalogForTesting();
        StorageEngine.TableMeta table1 = catalog1.tableByName("trips").get();

        assertEquals(1, table1.partitions.size());
        assertEquals(1, e.activePartitions(catalog1, 0).size());
        assertEquals(tripsRows.size(), ex.executeScript("SELECT * FROM trips;").getFirst().size());

        // This should delete all rows; no partition should be created
        ex.executeScript("DELETE FROM trips;");

        Catalog catalog2 = e.viewCatalogForTesting();
        StorageEngine.TableMeta table2 = catalog2.tableByName("trips").get();

        assertEquals(1, table2.partitions.size());
        assertEquals(0, e.activePartitions(catalog2, 0).size());
        assertFalse(Files.exists(tmp.resolve(tripsPartitionName(1))));
        assertEquals(0, ex.executeScript("SELECT * FROM trips;").getFirst().size());
    }

    void assertTableVersion(int expected, StorageEngine engine, String tableName) {
        int actual = engine.viewCatalogForTesting().tableByName(tableName).get().version;
        assertEquals(expected, actual, "Expected table version " + expected + ", but got " + actual);
    }

    String tripsPartitionName(int index) {
        return StorageEngine.partitionFileName("trips", index);
    }
}
