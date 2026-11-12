package dk.itu.datasys;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import dk.itu.datasys.BoundStatement.*;
import dk.itu.datasys.Spec.*;

public sealed interface BoundStatement permits CreateTable, Copy, Select, Delete {
    public static record CreateTable(String tableName, List<ColumnSpec> columns) implements BoundStatement {
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

    public static record Copy(int tableIndex, Path csvFilePath) implements BoundStatement {
        public String toString() {
            return "Copy[tableIndex=" + tableIndex + " csvFilePath=" + csvFilePath + "]";
        }
    }

    public static Copy copy(int tableIndex, Path csvFilePath) {
        return new Copy(tableIndex, csvFilePath);
    }

    public static record Predicate(int columnIndex, Comparison comparison, Value constant) {
        public boolean matchesNonNull(Value value) {
            return comparison.matchesNonNull(value, constant);
        }

        @Override
        public String toString() {
            return "Predicate[columnIndex=" + " comparison=" + comparison + " constant=" + constant + "]";
        }
    }

    public static record Select(Optional<List<Integer>> columnIndices, int tableIndex, Optional<Predicate> where, Optional<Integer> limit) implements BoundStatement {

        @Override
        public String toString() {
            String columns = columnIndices.stream().map(Object::toString).reduce("", (a, b) -> a + " " + b);
            return "Select[columns=[" + columns + "] tableIndex=" + tableIndex + " where=" + where + " limit=" + limit + "]";
        }

        public static class Builder {
            private final int tableIndex;
            private final List<Integer> columnIndices = new ArrayList<>();
            private Optional<Predicate> where = Optional.empty();
            private Optional<Integer> limit = Optional.empty();

            public Builder(int tableIndex) {
                this.tableIndex = tableIndex;
            }

            public Builder columns(List<Integer> columnIndices) {
                this.columnIndices.addAll(columnIndices);
                return this;
            }

            public Builder columns(Integer... columnIndices) {
                this.columnIndices.addAll(List.of(columnIndices));
                return this;
            }

            public Builder where(Optional<Predicate> where) {
                this.where = where;
                return this;
            }

            public Builder where(int columnIndex, Comparison comparison, Value constant) {
                this.where = Optional.of(new Predicate(columnIndex, comparison, constant));
                return this;
            }

            public Builder where(int columnIndex, Comparison comparison, String constant) {
                this.where = Optional.of(new Predicate(columnIndex, comparison, Value.of(constant)));
                return this;
            }

            public Builder where(int columnIndex, Comparison comparison, long constant) {
                this.where = Optional.of(new Predicate(columnIndex, comparison, Value.of(constant)));
                return this;
            }

            public Builder where(int columnIndex, Comparison comparison, double constant) {
                this.where = Optional.of(new Predicate(columnIndex, comparison, Value.of(constant)));
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
                Optional<List<Integer>> columns = columnIndices.isEmpty() ? Optional.empty() : Optional.of(List.copyOf(columnIndices));
                return new Select(columns, tableIndex, where, limit);
            }
        }
    }

    public static Select.Builder select(int tableIndex) {
        return new Select.Builder(tableIndex);
    }

    public static record Delete(int tableIndex, Optional<Predicate> where) implements BoundStatement {
        @Override
        public String toString() {
            return "Delete[tableIndex=" + tableIndex + " where=" + where + "]";
        }

        public static class Builder {
            private final int tableIndex;
            private Optional<Predicate> where = Optional.empty();
            
            public Builder(int tableIndex) {
                this.tableIndex = tableIndex;
            }

            public Builder where(Optional<Predicate> where) {
                this.where = where;
                return this;
            }

            public Builder where(int columnIndex, Comparison comparison, Value constant) {
                this.where = Optional.of(new Predicate(columnIndex, comparison, constant));
                return this;
            }

            public Builder where(int columnIndex, Comparison comparison, String constant) {
                this.where = Optional.of(new Predicate(columnIndex, comparison, Value.of(constant)));
                return this;
            }

            public Builder where(int columnIndex, Comparison comparison, long constant) {
                this.where = Optional.of(new Predicate(columnIndex, comparison, Value.of(constant)));
                return this;
            }

            public Builder where(int columnIndex, Comparison comparison, double constant) {
                this.where = Optional.of(new Predicate(columnIndex, comparison, Value.of(constant)));
                return this;
            }

            public Delete statement() {
                return new Delete(tableIndex, where);
            }
        }
    }

    public static Delete.Builder delete(int tableIndex) {
        return new Delete.Builder(tableIndex);
    }
}
