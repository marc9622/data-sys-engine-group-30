package dk.itu.datasys;

public final class Spec {
    public enum ColumnType {
        STRING("string"),
        LONG("long"),
        DOUBLE("double");

        public final String name;

        private ColumnType(String name) {
            this.name = name;
        }

        @Override
        public String toString() {
            return name;
        }

        public static ColumnType fromString(String name) throws IllegalArgumentException {
            for (ColumnType type : ColumnType.values()) {
                if (type.name.equalsIgnoreCase(name))
                    return type;
            }

            throw new IllegalArgumentException("Unknown column type: " + name);
        }
    }

    public record ColumnSpec(String name, ColumnType type) {
    }

    public enum Comparison {
        EQUALS,
        LESS_THAN,
        GREATER_THAN;
    }

    private Spec() {};
}

