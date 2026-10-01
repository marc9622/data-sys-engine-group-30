package dk.itu.datasys;

import java.util.List;
import java.util.Optional;

import dk.itu.datasys.Spec.*;
import dk.itu.datasys.Statement.*;

public sealed interface Statement permits CreateTable, Copy, Select {
    public static record CreateTable(String tableName, List<ColumnSpec> columns) implements Statement { }

    public static record Copy(String tableName, String csvFilePath) implements Statement { }

    public static record Select(List<String> columns, String tableName, Optional<Predicate> where) implements Statement {
        public Select(String tableName, Optional<Predicate> where) {
            this(List.of(), tableName, where);
        }

        public static record Predicate(String columnName, Comparison comparison, Object constant) {
            public boolean matches(Object value, ColumnType type) {
                return comparison.matches(value, constant, type);
            }
        }

    }
}
