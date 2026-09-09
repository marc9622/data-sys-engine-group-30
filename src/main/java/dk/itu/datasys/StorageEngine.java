package dk.itu.datasys;

import java.nio.file.Path;
import java.util.List;

import dk.itu.datasys.Spec.ColumnSpec;
import dk.itu.datasys.Spec.Comparison;

public final class StorageEngine {
    /** All persistent state (catalog + data files) lives under this directory. */
    public StorageEngine(Path dataDir) {
        throw new RuntimeException("todo");
    }

    public void createTable(String tableName, List<ColumnSpec> columns) {
        throw new RuntimeException("todo");
    }

    public void copyFile(String tableName, String csvFilePath) {
        throw new RuntimeException("todo");
    }

    public List<Object[]> select(String tableName, String columnName, Comparison comparison, Object constant) {
        throw new RuntimeException("todo");
    }
}
