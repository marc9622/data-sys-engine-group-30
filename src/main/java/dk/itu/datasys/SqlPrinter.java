package dk.itu.datasys;

import java.util.List;
import java.util.Objects;

import dk.itu.datasys.Spec.ColumnSpec;
import dk.itu.datasys.Spec.ColumnType;
import dk.itu.datasys.Spec.Comparison;

public final class SqlPrinter {
    public String print(Statement statement) {
        Objects.requireNonNull(statement, "statement");

        return switch (statement) {
            case Statement.CreateTable create -> printCreateTable(create);
            case Statement.Copy copy -> printCopy(copy);
            case Statement.Select select -> printSelect(select);
            default -> throw new IllegalArgumentException("unsupported statement: " + statement);
        };
    }

    private static String printCreateTable(Statement.CreateTable create) {
        StringBuilder sb = new StringBuilder();
        sb.append("CREATE TABLE ").append(create.tableName()).append(" (");

        for (int i = 0; i < create.columns().size(); i++) {
            ColumnSpec col = create.columns().get(i);
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(col.name()).append(" ").append(columnTypeSql(col.type()));
        }

        sb.append(");");
        return sb.toString();
    }

    private static String printCopy(Statement.Copy copy) {
        return "COPY " + copy.tableName() + " FROM '" + escapeSqlString(copy.csvFilePath()) + "';";
    }

    private static String printSelect(Statement.Select select) {
        StringBuilder sb = new StringBuilder();
        sb.append("SELECT * FROM ").append(select.tableName());

        if (select.where().isPresent()) {
            Statement.Select.Predicate predicate = select.where().get();
            sb.append(" WHERE ")
              .append(predicate.columnName())
              .append(" ")
              .append(comparisonSql(predicate.comparison()))
              .append(" ")
              .append(literalSql(predicate.constant()));
        }

        sb.append(";");
        return sb.toString();
    }

    private static String columnTypeSql(ColumnType type) {
        return switch (type) {
            case STRING -> "STRING";
            case LONG -> "LONG";
            case DOUBLE -> "DOUBLE";
        };
    }

    private static String comparisonSql(Comparison comparison) {
        return switch (comparison) {
            case EQUALS -> "=";
            case LESS_THAN -> "<";
            case GREATER_THAN -> ">";
        };
    }

    private static String literalSql(Object value) {
        if (value instanceof String s) {
            return "'" + escapeSqlString(s) + "'";
        }
        if (value instanceof Long l) {
            return Long.toString(l);
        }
        if (value instanceof Double d) {
            return Double.toString(d);
        }
        throw new IllegalArgumentException("unsupported literal type: " + value);
    }

    private static String escapeSqlString(String value) {
        return value.replace("'", "''");
    }
}
