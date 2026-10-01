package dk.itu.datasys;

import java.util.Objects;

public final class Spec {
    public enum ColumnType {
        STRING,
        LONG,
        DOUBLE;
    }

    public record ColumnSpec(String name, ColumnType type) { }

    public enum Comparison {
        EQUALS,
        LESS_THAN,
        GREATER_THAN;

        public static int compareNonNull(Object left, Object right, ColumnType type) {
            Objects.requireNonNull(left);
            Objects.requireNonNull(right);

            return switch (type) {
                case STRING -> ((String) left).compareTo((String) right);
                case LONG -> Long.compare((Long) left, (Long) right);
                case DOUBLE -> Double.compare((Double) left, (Double) right);
            };
        }

        public boolean matches(Object left, Object right, ColumnType type) {
            return switch (this) {
                case EQUALS -> {
                    if (left == null && right == null) yield true;
                    if (left == null || right == null) yield false;

                    yield compareNonNull(left, right, type) == 0;
                }
                case LESS_THAN -> {
                    if (left == null || right == null) yield false;

                    yield compareNonNull(left, right, type) < 0;
                }
                case GREATER_THAN -> {
                    if (left == null || right == null) yield false;

                    yield compareNonNull(left, right, type) > 0;
                }
            };
        }
    }
}

