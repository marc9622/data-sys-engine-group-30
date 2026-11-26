package dk.itu.datasys;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;

import dk.itu.datasys.Spec.ColumnSpec;
import dk.itu.datasys.StorageEngine.Catalog;

final class ColumnResolver {
    record Resolution(String tableName, int tableColumnIndex, int joinedColumnIndex, ColumnSpec column) {}

    private final List<String> tableNames;
    private final Catalog catalog;

    ColumnResolver(Catalog catalog, List<String> tableNames) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.tableNames = List.copyOf(tableNames);
        if (this.tableNames.isEmpty())
            throw new IllegalArgumentException("SELECT requires at least one table");

        HashSet<String> seen = new HashSet<>();
        for (String tableName : this.tableNames) {
            if (!seen.add(tableName))
                throw new IllegalArgumentException("table appears more than once in FROM list: " + tableName);
            if (!catalog.doesTableExist(tableName))
                throw new IllegalArgumentException("table does not exist: " + tableName);
        }
    }

    Resolution resolve(String columnName) {
        Resolution match = null;
        int joinedColumnIndex = 0;

        for (String tableName : tableNames) {
            List<ColumnSpec> schema = catalog.schema(tableName);
            for (int tableColumnIndex = 0; tableColumnIndex < schema.size(); tableColumnIndex++) {
                ColumnSpec column = schema.get(tableColumnIndex);
                if (column.name().equals(columnName)) {
                    if (match != null)
                        throw new IllegalArgumentException("ambiguous column: " + columnName);
                    match = new Resolution(tableName, tableColumnIndex, joinedColumnIndex, column);
                }
                joinedColumnIndex++;
            }
        }

        if (match == null)
            throw new IllegalArgumentException("unknown column: " + columnName);
        return match;
    }
}
