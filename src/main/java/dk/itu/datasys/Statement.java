package dk.itu.datasys;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import dk.itu.datasys.Spec.*;
import dk.itu.datasys.Statement.*;

public sealed interface Statement permits CreateTable, Copy, Select, Delete {
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

    public static sealed interface Predicate permits FilterPredicate, JoinPredicate {}

    public static record FilterPredicate(String columnName, Comparison comparison, Object constant) implements Predicate {
        public boolean matches(Object value, ColumnType type) {
            return comparison.matches(value, constant, type);
        }

        @Override
        public String toString() {
            return "FilterPredicate[columnName=" + columnName + " comparison=" + comparison + " constant=" + constant + "]";
        }
    }

    public static record JoinPredicate(String leftColumnName, String rightColumnName) implements Predicate {
        public boolean matches() {
            throw new RuntimeException("JoinPredicate.matches() is not implemented yet");
        }

        @Override
        public String toString() {
            return "JoinPredicate[leftColumnName=" + leftColumnName + " rightColumnName=" + rightColumnName + "]";
        }
    }

    public static record Select(List<String> columns, List<String> tableNames, List<FilterPredicate> filters, List<JoinPredicate> joins, Optional<Integer> limit) implements Statement {
        @Override
        public String toString() {
            return "Select[" +
                "columns=[" + String.join(" ", columns) + "]" +
                "tableName=[" + String.join(" ", tableNames) + "]" +
                "filters=[" + String.join(" ", filters.stream().map(Object::toString).toList()) + "] " +
                "joins=[" + String.join(" ", joins.stream().map(Object::toString).toList()) + "] " +
                "limit=" + limit +
                "]";
        }

        public static class Builder {
            private final List<String> tableNames = new ArrayList<>();
            private final List<String> columns = new ArrayList<>();
            private List<FilterPredicate> filters = new ArrayList<>();
            private List<JoinPredicate> joins = new ArrayList<>();
            private Optional<Integer> limit = Optional.empty();

            public Builder(String tableName) {
                this.tableNames.add(tableName);
            }

            public Builder(List<String> tableNames) {
                this.tableNames.addAll(tableNames);
            }

            public Builder columns(List<String> columns) {
                this.columns.addAll(columns);
                return this;
            }

            public Builder columns(String... columns) {
                this.columns.addAll(List.of(columns));
                return this;
            }

            public Builder where(List<FilterPredicate> filters) {
                this.filters.addAll(filters);
                return this;
            }

            public Builder where(FilterPredicate filter) {
                this.filters.add(filter);
                return this;
            }

            public Builder where(String columnName, Comparison comparison, Object constant) {
                this.filters.add(new FilterPredicate(columnName, comparison, constant));
                return this;
            }

            public Builder joinOn(List<JoinPredicate> joins) {
                this.joins.addAll(joins);
                return this;
            }

            public Builder joinOn(JoinPredicate joinPredicate) {
                this.joins.add(joinPredicate);
                return this;
            }

            public Builder joinOn(String leftColumnName, String rightColumnName) {
                this.joins.add(new JoinPredicate(leftColumnName, rightColumnName));
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
                return new Select(List.copyOf(columns), tableNames, filters, joins, limit);
            }
        }
    }

    public static Select.Builder select(String tableName) {
        return new Select.Builder(tableName);
    }

    public static Select.Builder select(List<String> tableNames) {
        return new Select.Builder(tableNames);
    }

    public static record Delete(String tableName, Optional<FilterPredicate> filter) implements Statement {
        public Select getMatchingSelect() {
            return new Select(List.of(), List.of(tableName), filter.stream().toList(), List.of(), Optional.empty());
        }

        @Override
        public String toString() {
            return "Delete[tableName=" + tableName + " where=" + filter + "]";
        }

        public static class Builder {
            private final String tableName;
            private Optional<FilterPredicate> where = Optional.empty();
            
            public Builder(String tableName) {
                this.tableName = tableName;
            }

            public Builder where(Optional<FilterPredicate> where) {
                this.where = where;
                return this;
            }

            public Builder where(String columnName, Comparison comparison, Object constant) {
                this.where = Optional.of(new FilterPredicate(columnName, comparison, constant));
                return this;
            }

            public Delete statement() {
                return new Delete(tableName, where);
            }
        }
    }

    public static Delete.Builder delete(String tableName) {
        return new Delete.Builder(tableName);
    }
}
