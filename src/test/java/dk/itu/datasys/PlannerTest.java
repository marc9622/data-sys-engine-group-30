package dk.itu.datasys;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dk.itu.datasys.Spec.*;
import dk.itu.datasys.Statement.Select;
import static dk.itu.datasys.Statement.*;
import dk.itu.datasys.ops.FilterOperator;
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
        engine.createTable("trips", COLUMNS);
        engine.copyFromCsvFile("trips", Utils.resource("trips_sorted.csv").toString());
        Planner planner = new Planner(engine);
        Select select = select("trips").where("distance", Comparison.EQUALS, 95L).statement();

        Plan plan = planner.plan(select);

        assertInstanceOf(FilterOperator.class, plan.root());
        assertEquals(new StorageEngine.ScanStats(4, 1, 3), plan.scanStats());
    }

    @Test
    void noWhereProducesScanOverAllPartitions(@TempDir Path tmp) {
        StorageEngine engine = new StorageEngine(tmp);
        engine.createTable("trips", COLUMNS);
        engine.copyFromCsvFile("trips", Utils.resource("trips_sorted.csv").toString());
        Planner planner = new Planner(engine);

        Plan plan = planner.plan(select("trips").statement());

        assertInstanceOf(ScanOperator.class, plan.root());
        assertEquals(new StorageEngine.ScanStats(4, 4, 0), plan.scanStats());
    }
}
