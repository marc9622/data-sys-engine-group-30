package dk.itu.datasys;

import static dk.itu.datasys.TestingUtils.*;
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
        createTableWithCatalog(engine, "trips", List.of(
                new ColumnSpec("city", ColumnType.STRING),
                new ColumnSpec("distance", ColumnType.LONG),
                new ColumnSpec("price", ColumnType.DOUBLE)));

        assertDoesNotThrow(() -> Binder.bindStatement(
                    engine.viewCatalogForTesting(),
                    Statement.copy("trips", "trips.csv")));
        assertDoesNotThrow(() -> Binder.bindStatement(
                    engine.viewCatalogForTesting(),
                    Statement.createTable("other")
                        .addColumn("name", ColumnType.STRING)
                        .statement()));
        assertDoesNotThrow(() -> Binder.bindStatement(
                    engine.viewCatalogForTesting(),
                    Statement.select("trips")
                        .where("distance", Comparison.GREATER_THAN, 100L)
                        .statement()));
        assertDoesNotThrow(() -> Binder.bindStatement(
                    engine.viewCatalogForTesting(),
                    Statement.delete("trips")
                        .where("distance", Comparison.LESS_THAN, 50L)
                        .statement()));
    }

    @Test
    void invalidStatementsThrow(@TempDir Path tempDir) {
        StorageEngine engine = new StorageEngine(tempDir);
        createTableWithCatalog(engine, "trips", List.of(
                new ColumnSpec("city", ColumnType.STRING),
                new ColumnSpec("distance", ColumnType.LONG),
                new ColumnSpec("price", ColumnType.DOUBLE)));

        assertThrows(IllegalArgumentException.class, () -> Binder.bindStatement(
                    engine.viewCatalogForTesting(),
                    Statement.select("missing")
                        .statement()));
        assertThrows(IllegalArgumentException.class, () -> Binder.bindStatement(
                    engine.viewCatalogForTesting(),
                    Statement.select("trips")
                        .where("missing", Comparison.EQUALS, 10L)
                        .statement()));
        assertThrows(IllegalArgumentException.class, () -> Binder.bindStatement(
                    engine.viewCatalogForTesting(),
                    Statement.select("trips")
                        .where("distance", Comparison.EQUALS, "x")
                        .statement()));
        assertThrows(IllegalArgumentException.class, () -> Binder.bindStatement(
                    engine.viewCatalogForTesting(),
                    Statement.createTable("duplicates")
                        .addColumn("a", ColumnType.STRING)
                        .addColumn("a", ColumnType.LONG)
                        .statement()));
    }

    @Test
    void invalidDeleteStatementsThrow(@TempDir Path tempDir) {
        StorageEngine engine = new StorageEngine(tempDir);
        createTableWithCatalog(engine, "trips", tripsColumns);

        assertThrows(IllegalArgumentException.class, () -> Binder.bindStatement(
                    engine.viewCatalogForTesting(),
                    Statement.delete("missing")
                        .statement()));
        assertThrows(IllegalArgumentException.class, () -> Binder.bindStatement(
                    engine.viewCatalogForTesting(),
                    Statement.delete("trips")
                        .where("missing", Comparison.EQUALS, 10L)
                        .statement()));
        assertThrows(IllegalArgumentException.class, () -> Binder.bindStatement(
                    engine.viewCatalogForTesting(),
                    Statement.delete("trips")
                        .where("distance", Comparison.EQUALS, "not a long")
                        .statement()));
    }

    @Test
    void rejectsAmbiguousColumnInMultipleTables(@TempDir Path tempDir) {
        StorageEngine engine = new StorageEngine(tempDir);
        createTableWithCatalog(engine, "orders", List.of(
                new ColumnSpec("id", ColumnType.LONG),
                new ColumnSpec("o_name", ColumnType.STRING)));
        createTableWithCatalog(engine, "customers", List.of(
                new ColumnSpec("id", ColumnType.LONG),
                new ColumnSpec("c_name", ColumnType.STRING)));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> 
            Binder.bindStatement(
                engine.viewCatalogForTesting(),
                Statement.select(List.of("orders", "customers"))
                    .joinOn("id", "c_name")
                    .statement()));
        
        assertTrue(error.getMessage().contains("ambiguous") || error.getMessage().contains("Ambiguous"),
                "Error message should mention ambiguous column");
    }

    @Test
    void rejectsTypeMismatchedJoin(@TempDir Path tempDir) {
        StorageEngine engine = new StorageEngine(tempDir);
        createTableWithCatalog(engine, "orders", List.of(
                new ColumnSpec("o_orderkey", ColumnType.LONG),
                new ColumnSpec("o_name", ColumnType.STRING)));
        createTableWithCatalog(engine, "products", List.of(
                new ColumnSpec("p_price", ColumnType.DOUBLE),
                new ColumnSpec("p_name", ColumnType.STRING)));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> 
            Binder.bindStatement(
                engine.viewCatalogForTesting(),
                Statement.select(List.of("orders", "products"))
                    .joinOn("o_orderkey", "p_price")
                    .statement()));
        
        assertTrue(error.getMessage().contains("type") || error.getMessage().contains("Type") || 
                   error.getMessage().contains("mismatch"),
                "Error message should mention type mismatch");
    }

    @Test
    void rejectsJoinWithBothSidesInSameTable(@TempDir Path tempDir) {
        StorageEngine engine = new StorageEngine(tempDir);
        createTableWithCatalog(engine, "orders", List.of(
                new ColumnSpec("o_orderkey", ColumnType.LONG),
                new ColumnSpec("o_custkey", ColumnType.LONG)));
        createTableWithCatalog(engine, "products", List.of(
                new ColumnSpec("p_name", ColumnType.STRING)));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> 
            Binder.bindStatement(
                engine.viewCatalogForTesting(),
                Statement.select(List.of("orders", "products"))
                    .joinOn("o_orderkey", "o_custkey")
                    .statement()));
        
        assertTrue(error.getMessage().contains("different"),
                "Error message should indicate the join columns must be from different tables: " + error.getMessage());
    }
}
