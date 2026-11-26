package dk.itu.datasys;

import static dk.itu.datasys.Statement.*;
import static dk.itu.datasys.TestingUtils.*;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dk.itu.datasys.Spec.*;
import dk.itu.datasys.Statement.Select;
import dk.itu.datasys.ops.FilterOperator;
import dk.itu.datasys.ops.HashJoinOperator;
import dk.itu.datasys.ops.ScanOperator;

class PlannerTest {
    private static final List<ColumnSpec> COLUMNS = List.of(
            new ColumnSpec("city", ColumnType.STRING),
            new ColumnSpec("distance", ColumnType.LONG),
            new ColumnSpec("price", ColumnType.DOUBLE));

    @BeforeAll
    static void configurePartitions() {
        System.setProperty("maxRowsPerPartition", "2");
    }


    @Test
    void whereProducesFilterOverScan(@TempDir Path tmp) {
        StorageEngine engine = new StorageEngine(tmp);
        createTableWithCatalog(engine, "trips", COLUMNS);
        copyFromCsvFileWithCatalog(engine, "trips", resource("trips_sorted.csv"));
        Planner planner = new Planner(engine);
        Select select = select("trips").where("distance", Comparison.EQUALS, 95L).statement();

        Plan plan = planner.plan(engine.openCatalog(), select);

        assertInstanceOf(FilterOperator.class, plan.root());
        assertEquals(new StorageEngine.ScanStats(4, 1, 3), plan.scanStats());
    }

    @Test
    void noWhereProducesScanOverAllPartitions(@TempDir Path tmp) {
        StorageEngine engine = new StorageEngine(tmp);
        createTableWithCatalog(engine, "trips", COLUMNS);
        copyFromCsvFileWithCatalog(engine, "trips", resource("trips_sorted.csv"));
        Planner planner = new Planner(engine);

        Plan plan = planner.plan(engine.openCatalog(), select("trips").statement());

        assertInstanceOf(ScanOperator.class, plan.root());
        assertEquals(new StorageEngine.ScanStats(4, 4, 0), plan.scanStats());
    }

    @Test
    void plansFourTableJoinLeftToRightInFromOrder(@TempDir Path tmp) {
        StorageEngine engine = new StorageEngine(tmp);
        Select fourTableQuery = loadTestbedData(engine);
        Planner planner = new Planner(engine);

        StorageEngine.Catalog catalog = engine.openCatalog();
        try {
            Plan unfilteredPlan = planner.plan(catalog, fourTableQuery);

            assertInstanceOf(HashJoinOperator.class, unfilteredPlan.root());
            assertEquals(List.of("o_orderkey", "o_custname", "o_prodname", "o_quantity",
                            "c_name", "c_city", "p_name", "p_price", "ct_city", "ct_country", "ct_population"),
                    unfilteredPlan.root().schema().stream().map(ColumnSpec::name).toList());
            assertEquals(4, exhaust(unfilteredPlan).size());

            Select filteredQuery = select(fourTableQuery.tableNames())
                    .joinOn(fourTableQuery.joins())
                    .where("o_orderkey", Comparison.EQUALS, 100L)
                    .statement();
            Plan filteredPlan = planner.plan(catalog, filteredQuery);

            assertEquals(new StorageEngine.ScanStats(6, 5, 1), filteredPlan.scanStats());
            List<Object[]> rows = exhaust(filteredPlan);
            assertEquals(1, rows.size());
            assertArrayEquals(new Object[] {
                    100L, "Anna", "Bike", 1L,
                    "Anna", "Copenhagen",
                    "Bike", 2999.0,
                    "Copenhagen", "Denmark", 1_400_000L
            }, rows.getFirst());
        } finally {
            engine.closeCatalog(catalog);
        }
    }

    @Test
    void rejectsTableWithoutJoinToEarlierTableAndSuggestsReordering(@TempDir Path tmp) {
        StorageEngine engine = new StorageEngine(tmp);
        createTableWithCatalog(engine, "first", List.of(new ColumnSpec("first_key", ColumnType.STRING)));
        createTableWithCatalog(engine, "isolated", List.of(new ColumnSpec("isolated_key", ColumnType.STRING)));
        Planner planner = new Planner(engine);
        Select select = select(List.of("first", "isolated")).statement();

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> planner.plan(engine.openCatalog(), select));

        assertTrue(error.getMessage().contains("reorder the FROM list"));
    }

    @Test
    void rejectsMultiplePredicatesConnectingTheSameTable(@TempDir Path tmp) {
        StorageEngine engine = new StorageEngine(tmp);
        createTableWithCatalog(engine, "left_table", List.of(
                new ColumnSpec("left_key", ColumnType.STRING),
                new ColumnSpec("left_other", ColumnType.STRING)));
        createTableWithCatalog(engine, "right_table", List.of(
                new ColumnSpec("right_key", ColumnType.STRING),
                new ColumnSpec("right_other", ColumnType.STRING)));
        Planner planner = new Planner(engine);
        Select select = select(List.of("left_table", "right_table"))
                .joinOn("left_key", "right_key")
                .joinOn("left_other", "right_other")
                .statement();

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> planner.plan(engine.openCatalog(), select));

        assertTrue(error.getMessage().contains("only one connecting predicate"));
    }

    private static Select loadTestbedData(StorageEngine engine) {
        createTableWithCatalog(engine, "orders", ordersColumns);
        createTableWithCatalog(engine, "customers", customersColumns);
        createTableWithCatalog(engine, "products", productsColumns);
        createTableWithCatalog(engine, "cities", citiesColumns);

        copyFromCsvFileWithCatalog(engine, "orders", resource("orders.csv"));
        copyFromCsvFileWithCatalog(engine, "customers", resource("customers.csv"));
        copyFromCsvFileWithCatalog(engine, "products", resource("products.csv"));
        copyFromCsvFileWithCatalog(engine, "cities", resource("cities.csv"));

        return select(List.of("orders", "customers", "products", "cities"))
                .joinOn("o_custname", "c_name")
                .joinOn("o_prodname", "p_name")
                .joinOn("c_city", "ct_city")
                .statement();
    }

    private static List<Object[]> exhaust(Plan plan) {
        plan.root().open();
        try {
            return plan.root().exhaust();
        } finally {
            plan.root().close();
        }
    }
}
