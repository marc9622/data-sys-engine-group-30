package dk.itu.datasys;

import java.util.ArrayList;
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

        public static class Builder {
            private final List<ColumnSpec> columns = new ArrayList<>();
            private final String tableName;

            public Builder(String tableName) {
                this.tableName = tableName;
            }

            public Builder addColumn(String columnName, ColumnType columnType) {
                columns.add(new ColumnSpec(columnName, columnType));
                return this;
            }

            public Builder addColumns(List<ColumnSpec> columns) {
                this.columns.addAll(columns);
                return this;
            }

            public CreateTable statement() {
                return new CreateTable(tableName, List.copyOf(columns));
            }
        }
    }

    public static CreateTable.Builder createTable(String tableName) {
        return new CreateTable.Builder(tableName);
    }

    public static record Copy(String tableName, String csvFilePath) implements Statement {
        @Override
        public String toString() {
            return "Copy[tableName=" + tableName + " csvFilePath=" + csvFilePath + "]";
        }
    }

    public static Copy copy(String tableName, String csvFilePath) {
        return new Copy(tableName, csvFilePath);
    }

    public static record Select(List<String> columns, String tableName, Optional<Predicate> where, Optional<Integer> limit) implements Statement {
        public Select(String tableName, Optional<Predicate> where, Optional<Integer> limit) {
            this(List.of(), tableName, where, limit);
        }

        @Override
        public String toString() {
            return "Select[columns=" + columns + " tableName=" + tableName + " where=" + where + " limit=" + limit + "]";
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

        public static class Builder {
            private final String tableName;
            private final List<String> columns = new ArrayList<>();
            private Optional<Predicate> where = Optional.empty();
            private Optional<Integer> limit = Optional.empty();

            public Builder(String tableName) {
                this.tableName = tableName;
            }

            public Builder columns(List<String> columns) {
                this.columns.addAll(columns);
                return this;
            }

            public Builder columns(String... columns) {
                this.columns.addAll(List.of(columns));
                return this;
            }

            public Builder where(Optional<Predicate> where) {
                this.where = where;
                return this;
            }

            public Builder where(String columnName, Comparison comparison, Object constant) {
                this.where = Optional.of(new Predicate(columnName, comparison, constant));
                return this;
            }

            public Builder limit(Optional<Integer> limit) {
                this.limit = limit;
                return this;
            }

            public Builder limit(int limit) {
                this.limit = Optional.of(limit);
                return this;
            }

            public Select statement() {
                return new Select(List.copyOf(columns), tableName, where, limit);
            }
        }
    }

    public static Select.Builder select(String tableName) {
        return new Select.Builder(tableName);
    }
}
