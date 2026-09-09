package dk.itu.datasys;

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
    }
}

