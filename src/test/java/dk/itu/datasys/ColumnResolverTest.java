package dk.itu.datasys;

import static dk.itu.datasys.TestingUtils.*;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dk.itu.datasys.Spec.*;
import dk.itu.datasys.StorageEngine.Catalog;

class ColumnResolverTest {

    @Test
    void resolvesColumnToTableAndIndex(@TempDir Path tempDir) {
        StorageEngine engine = new StorageEngine(tempDir);
        
        createTableWithCatalog(engine, "orders", List.of(
                new ColumnSpec("o_orderkey", ColumnType.LONG),
                new ColumnSpec("o_custname", ColumnType.STRING)));
        createTableWithCatalog(engine, "customers", List.of(
                new ColumnSpec("c_name", ColumnType.STRING),
                new ColumnSpec("c_city", ColumnType.STRING)));
        
        Catalog catalog = engine.viewCatalogForTesting();
        ColumnResolver resolver = new ColumnResolver(catalog, List.of("orders", "customers"));
        
        ColumnResolver.Resolution orderKey = resolver.resolve("o_orderkey");
        assertEquals("orders", orderKey.tableName());
        assertEquals(0, orderKey.tableColumnIndex());
        assertEquals(0, orderKey.joinedColumnIndex());
        assertEquals(ColumnType.LONG, orderKey.column().type());
        
        ColumnResolver.Resolution custName = resolver.resolve("o_custname");
        assertEquals("orders", custName.tableName());
        assertEquals(1, custName.tableColumnIndex());
        assertEquals(1, custName.joinedColumnIndex());
        
        ColumnResolver.Resolution cName = resolver.resolve("c_name");
        assertEquals("customers", cName.tableName());
        assertEquals(0, cName.tableColumnIndex());
        assertEquals(2, cName.joinedColumnIndex());
        
        ColumnResolver.Resolution cCity = resolver.resolve("c_city");
        assertEquals("customers", cCity.tableName());
        assertEquals(1, cCity.tableColumnIndex());
        assertEquals(3, cCity.joinedColumnIndex());
    }

    @Test
    void resolvesColumnsInConcatenatedRowOrder(@TempDir Path tempDir) {
        StorageEngine engine = new StorageEngine(tempDir);
        
        createTableWithCatalog(engine, "t1", List.of(
                new ColumnSpec("a", ColumnType.LONG),
                new ColumnSpec("b", ColumnType.STRING)));
        createTableWithCatalog(engine, "t2", List.of(
                new ColumnSpec("c", ColumnType.DOUBLE),
                new ColumnSpec("d", ColumnType.STRING)));
        createTableWithCatalog(engine, "t3", List.of(
                new ColumnSpec("e", ColumnType.LONG)));
        
        Catalog catalog = engine.viewCatalogForTesting();
        ColumnResolver resolver = new ColumnResolver(catalog, List.of("t1", "t2", "t3"));
        
        assertEquals(0, resolver.resolve("a").joinedColumnIndex());
        assertEquals(1, resolver.resolve("b").joinedColumnIndex());
        assertEquals(2, resolver.resolve("c").joinedColumnIndex());
        assertEquals(3, resolver.resolve("d").joinedColumnIndex());
        assertEquals(4, resolver.resolve("e").joinedColumnIndex());
    }

    @Test
    void throwsOnUnknownColumn(@TempDir Path tempDir) {
        StorageEngine engine = new StorageEngine(tempDir);
        
        createTableWithCatalog(engine, "orders", List.of(
                new ColumnSpec("o_orderkey", ColumnType.LONG)));
        
        Catalog catalog = engine.viewCatalogForTesting();
        ColumnResolver resolver = new ColumnResolver(catalog, List.of("orders"));
        
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> resolver.resolve("unknown_column"));
        
        assertTrue(error.getMessage().contains("unknown"));
    }

    @Test
    void throwsOnAmbiguousColumn(@TempDir Path tempDir) {
        StorageEngine engine = new StorageEngine(tempDir);
        
        createTableWithCatalog(engine, "orders", List.of(
                new ColumnSpec("id", ColumnType.LONG)));
        createTableWithCatalog(engine, "customers", List.of(
                new ColumnSpec("id", ColumnType.LONG)));
        
        Catalog catalog = engine.viewCatalogForTesting();
        ColumnResolver resolver = new ColumnResolver(catalog, List.of("orders", "customers"));
        
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> resolver.resolve("id"));
        
        assertTrue(error.getMessage().contains("ambiguous"));
    }

    @Test
    void throwsOnNonExistentTable(@TempDir Path tempDir) {
        StorageEngine engine = new StorageEngine(tempDir);
        
        createTableWithCatalog(engine, "orders", List.of(
                new ColumnSpec("o_orderkey", ColumnType.LONG)));
        
        Catalog catalog = engine.viewCatalogForTesting();
        
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new ColumnResolver(catalog, List.of("orders", "missing_table")));
        
        assertTrue(error.getMessage().contains("does not exist") || error.getMessage().contains("missing_table"));
    }

    @Test
    void throwsOnDuplicateTableInFromList(@TempDir Path tempDir) {
        StorageEngine engine = new StorageEngine(tempDir);
        
        createTableWithCatalog(engine, "orders", List.of(
                new ColumnSpec("o_orderkey", ColumnType.LONG)));
        
        Catalog catalog = engine.viewCatalogForTesting();
        
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new ColumnResolver(catalog, List.of("orders", "orders")));
        
        assertTrue(error.getMessage().contains("appears more than once") || 
                   error.getMessage().contains("duplicate"));
    }

    @Test
    void throwsOnEmptyTableList(@TempDir Path tempDir) {
        StorageEngine engine = new StorageEngine(tempDir);
        
        Catalog catalog = engine.viewCatalogForTesting();
        
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new ColumnResolver(catalog, List.of()));
        
        assertTrue(error.getMessage().contains("requires at least one table") || 
                   error.getMessage().contains("at least one"));
    }
}
