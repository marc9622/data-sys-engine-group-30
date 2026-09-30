package dk.itu.datasys;

import java.util.List;
import java.util.Optional;

import dk.itu.datasys.Spec.*;
import dk.itu.datasys.Statement.*;

public sealed interface Statement permits CreateTable, Copy, Select {
    public static record CreateTable(String tableName, List<ColumnSpec> columns) implements Statement { }

    public static record Copy(String tableName, String csvFilePath) implements Statement { }

    public static record Select(String tableName, Optional<Predicate> where) implements Statement {

        public static record Predicate(String columnName, Comparison comparison, Object constant) {
            public boolean matches(Object value, ColumnType type) {
                int comparisonResult = compare(value, constant, type);
                return switch (comparison) {
                    case EQUALS -> comparisonResult == 0;
                    case LESS_THAN -> comparisonResult < 0;
                    case GREATER_THAN -> comparisonResult > 0;
                };
            }

            private static int compare(Object left, Object right, ColumnType type) {
                if (left == null && right == null)
                    return 0;
                if (left == null)
                    return -1;
                if (right == null)
                    return 1;

                return switch (type) {
                    case STRING -> ((String) left).compareTo((String) right);
                    case LONG -> Long.compare((Long) left, (Long) right);
                    case DOUBLE -> Double.compare((Double) left, (Double) right);
                };
            }
        }

    }
}
