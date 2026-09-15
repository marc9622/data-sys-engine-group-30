package dk.itu.datasys;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dk.itu.datasys.Spec.ColumnSpec;
import dk.itu.datasys.Spec.ColumnType;
import dk.itu.datasys.Spec.Comparison;

class BinderTest {

    @Test
    void validStatementsBind(@TempDir Path tempDir) { 
        StorageEngine engine = new StorageEngine(tempDir);
        engine.createTable("trips", List.of(
                new ColumnSpec("city", ColumnType.STRING),
                new ColumnSpec("distance", ColumnType.LONG),
                new ColumnSpec("price", ColumnType.DOUBLE)));

        Binder binder = new Binder(engine);

        assertDoesNotThrow(() -> binder.bind(new Statement.Copy("trips", "trips.csv")));
        assertDoesNotThrow(() -> binder.bind(new Statement.Select("trips",
                Optional.of(new Statement.Select.Predicate("distance", Comparison.GREATER_THAN, 100L))))); 
        assertDoesNotThrow(() -> binder.bind(new Statement.CreateTable("other", List.of(
                new ColumnSpec("name", ColumnType.STRING)))));
    }

    @Test
    void invalidStatementsThrow(@TempDir Path tempDir) {
        StorageEngine engine = new StorageEngine(tempDir);
        engine.createTable("trips", List.of(
                new ColumnSpec("city", ColumnType.STRING),
                new ColumnSpec("distance", ColumnType.LONG),
                new ColumnSpec("price", ColumnType.DOUBLE)));

        Binder binder = new Binder(engine);

        assertThrows(IllegalArgumentException.class,
                () -> binder.bind(new Statement.Select("missing", Optional.empty())));
        assertThrows(IllegalArgumentException.class,
                () -> binder.bind(new Statement.Select("trips",
                        Optional.of(new Statement.Select.Predicate("missing", Comparison.EQUALS, 10L)))));
        assertThrows(IllegalArgumentException.class,
                () -> binder.bind(new Statement.Select("trips",
                        Optional.of(new Statement.Select.Predicate("distance", Comparison.EQUALS, "x")))));
        assertThrows(IllegalArgumentException.class,
                () -> binder.bind(new Statement.CreateTable("duplicates", List.of(
                        new ColumnSpec("a", ColumnType.STRING),
                        new ColumnSpec("a", ColumnType.LONG)))));
    }
}
