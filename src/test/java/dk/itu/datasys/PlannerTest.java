package dk.itu.datasys;

import static dk.itu.datasys.BoundStatement.*;
import static dk.itu.datasys.TestingUtils.*;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dk.itu.datasys.Spec.*;
import dk.itu.datasys.BoundStatement.Select;
import dk.itu.datasys.ops.FilterOperator;
import dk.itu.datasys.ops.ScanOperator;

class PlannerTest {
    @BeforeAll
    static void configurePartitions() {
        System.setProperty("maxRowsPerPartition", "2");
    }


    @Test
    void whereProducesFilterOverScan(@TempDir Path tmp) {
        StorageEngine engine = new StorageEngine(tmp);
        createTableWithCatalog(engine, "trips", tripsColumns);
        copyFromCsvFileWithCatalog(engine, "trips", resource("trips_sorted.csv"));
        Planner planner = new Planner(engine);
        Select select = select(0).where(tripsColumnIndexOf("distance"), Comparison.EQUALS, 95L).statement();

        Plan plan = planner.plan(engine.openCatalog(), select);

        assertInstanceOf(FilterOperator.class, plan.root());
        assertEquals(new StorageEngine.ScanStats(4, 1, 3), plan.scanStats());
    }

    @Test
    void noWhereProducesScanOverAllPartitions(@TempDir Path tmp) {
        StorageEngine engine = new StorageEngine(tmp);
        createTableWithCatalog(engine, "trips", tripsColumns);
        copyFromCsvFileWithCatalog(engine, "trips", resource("trips_sorted.csv"));
        Planner planner = new Planner(engine);

        Plan plan = planner.plan(engine.openCatalog(), select(0).statement());

        assertInstanceOf(ScanOperator.class, plan.root());
        assertEquals(new StorageEngine.ScanStats(4, 4, 0), plan.scanStats());
    }
}
