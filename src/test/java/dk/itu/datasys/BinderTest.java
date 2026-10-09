package dk.itu.datasys;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dk.itu.datasys.Spec.*;

class BinderTest {

    @Test
    void validStatementsBind(@TempDir Path tempDir) { 
        StorageEngine engine = new StorageEngine(tempDir);
        engine.createTable("trips", List.of(
                new ColumnSpec("city", ColumnType.STRING),
                new ColumnSpec("distance", ColumnType.LONG),
                new ColumnSpec("price", ColumnType.DOUBLE)));

        Binder binder = new Binder(engine);

        assertDoesNotThrow(() -> binder.bind(
                    Statement.copy("trips", "trips.csv")));
        assertDoesNotThrow(() -> binder.bind(
                    Statement.select("trips")
                    .where("distance", Comparison.GREATER_THAN, 100L)
                    .statement()));
        assertDoesNotThrow(() -> binder.bind(
                    Statement.createTable("other")
                    .addColumn("name", ColumnType.STRING)
                    .statement()));
    }

    @Test
    void invalidStatementsThrow(@TempDir Path tempDir) {
        StorageEngine engine = new StorageEngine(tempDir);
        engine.createTable("trips", List.of(
                new ColumnSpec("city", ColumnType.STRING),
                new ColumnSpec("distance", ColumnType.LONG),
                new ColumnSpec("price", ColumnType.DOUBLE)));

        Binder binder = new Binder(engine);

        assertThrows(IllegalArgumentException.class, () -> binder.bind(
                    Statement.select("missing")
                    .statement()));
        assertThrows(IllegalArgumentException.class, () -> binder.bind(
                    Statement.select("trips")
                    .where("missing", Comparison.EQUALS, 10L)
                    .statement()));
        assertThrows(IllegalArgumentException.class, () -> binder.bind(
                    Statement.select("trips")
                    .where("distance", Comparison.EQUALS, "x")
                    .statement()));
        assertThrows(IllegalArgumentException.class, () -> binder.bind(
                    Statement.createTable("duplicates")
                    .addColumn("a", ColumnType.STRING)
                    .addColumn("a", ColumnType.LONG)
                    .statement()));
    }
}
