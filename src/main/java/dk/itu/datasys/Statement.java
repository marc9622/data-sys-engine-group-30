package dk.itu.datasys;

import java.util.List;
import java.util.Optional;

import dk.itu.datasys.Spec.*;
import dk.itu.datasys.Statement.*;

public sealed interface Statement permits CreateTable, Copy, Select {
    public static record CreateTable(String tableName, List<ColumnSpec> columns) implements Statement {
        @Override
        public String toString() {
            String columnsString = String.join(" ", columns.stream().map(Object::toString).toList());
            return "CreateTable[tableName=" + tableName + " columns={" + columnsString + "}]";
        }
    }

    public static record Copy(String tableName, String csvFilePath) implements Statement {
        @Override
        public String toString() {
            return "Copy[tableName=" + tableName + " csvFilePath=" + csvFilePath + "]";
        }
    }

    public static record Select(List<String> columns, String tableName, Optional<Predicate> where) implements Statement {
        public Select(String tableName, Optional<Predicate> where) {
            this(List.of(), tableName, where);
        }

        @Override
        public String toString() {
            return "Select[columns=" + columns + " tableName=" + tableName + " where=" + where + "]";
        }

        public static record Predicate(String columnName, Comparison comparison, Object constant) {
            public boolean matches(Object value, ColumnType type) {
                return comparison.matches(value, constant, type);
            }

            @Override
            public String toString() {
                return "Predicate[columnName=" + columnName + " comparison=" + comparison + " constant=" + constant + "]";
            }
        }
    }
}
