package dk.itu.datasys;

import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonTypeName;

public final class Spec {
    public static enum ColumnType {
        STRING,
        LONG,
        DOUBLE;
    }

    @JsonTypeInfo(
        use = JsonTypeInfo.Id.NAME,
        include = JsonTypeInfo.As.PROPERTY,
        property = "type"
    )
    @JsonSubTypes({
        @JsonSubTypes.Type(value = Value.String.class, name = "string"),
        @JsonSubTypes.Type(value = Value.Long.class, name = "long"),
        @JsonSubTypes.Type(value = Value.Double.class, name = "double"),
    })
    public static sealed interface Value permits Value.Comparable {
        public default ColumnType type() {
            return switch (this) {
                case String(_) -> ColumnType.STRING;
                case Long(_) -> ColumnType.LONG;
                case Double(_) -> ColumnType.DOUBLE;
            };
        }

        public static String of(java.lang.String string) {
            return new Value.String(string);
        }

        public static Long of(long value) {
            return new Value.Long(value);
        }

        public static Double of(double value) {
            return new Value.Double(value);
        }

        public static Value fromObject(Object object) {
            return switch (object) {
                case java.lang.String s -> new Value.String(s);
                case java.lang.Long l -> new Value.Long(l);
                case java.lang.Double d -> new Value.Double(d);
                case Value _ -> throw new IllegalArgumentException("Already a Value: " + object);
                default -> throw new IllegalArgumentException("Unsupported value type: " + object.getClass());
            };
        }

        public static Value fromObject(Object object, ColumnType type) {
            Objects.requireNonNull(type);

            return switch (type) {
                case STRING -> new Value.String((java.lang.String) object);
                case LONG -> new Value.Long((long) object);
                case DOUBLE -> new Value.Double((double) object);
            };
        }

        public static String[] arrayOf(java.lang.String... strings) {
            String[] values = new String[strings.length];
            for (int i = 0; i < strings.length; i++)
                values[i] = new Value.String(strings[i]);

            return values;
        }

        public static Long[] arrayOf(long... longs) {
            Long[] values = new Long[longs.length];
            for (int i = 0; i < longs.length; i++)
                values[i] = new Value.Long(longs[i]);

            return values;
        }

        public static Double[] arrayOf(double... doubles) {
            Double[] values = new Double[doubles.length];
            for (int i = 0; i < doubles.length; i++)
                values[i] = new Value.Double(doubles[i]);

            return values;
        }

        public static Value[] arrayFromObjects(Object... objects) {
            Value[] values = new Value[objects.length];
            for (int i = 0; i < objects.length; i++)
                values[i] = fromObject(objects[i]);

            return values;
        }

        public default Value.String anyToString() {
            return switch (this) {
                case Value.String s -> s;
                default -> throw new IllegalArgumentException("Cannot convert to String: " + this);
            };
        }

        public default Value.Long anyToLong() {
            return switch (this) {
                case Value.Long l -> l;
                default -> throw new IllegalArgumentException("Cannot convert to Long: " + this);
            };
        }

        public default Value.Double anyToDouble() {
            return switch (this) {
                case Value.Double d -> d;
                default -> throw new IllegalArgumentException("Cannot convert to Double: " + this);
            };
        }

        public default Object toObject() {
            return switch (this) {
                case Value.String s -> s.value();
                case Value.Long l -> l.value();
                case Value.Double d -> d.value();
            };
        }

        public static sealed interface Comparable<T> extends Value {
            public int compareWith(T right);

            default int compareWithAnyValue(Value right) {
                return switch (this) {
                    case Value.String leftString when right instanceof Value.String rightString ->
                        leftString.compareWith(rightString);
                    case Value.Long leftLong when right instanceof Value.Long rightLong ->
                        leftLong.compareWith(rightLong);
                    case Value.Double leftDouble when right instanceof Value.Double rightDouble ->
                        leftDouble.compareWith(rightDouble);
                    default -> throw new IllegalArgumentException("Cannot compare different types: " + this + " and " + right);
                };
            }
        }

        public default int compareAnyWithAny(Value right) {
            return switch (this) {
                case Comparable<?> comparable -> comparable.compareWithAnyValue(right);
            };
        }

        @JsonTypeName("string")
        public static record String(java.lang.String value) implements Comparable<String> {
            public int compareWith(String right) {
                return value.compareTo(right.value);
            }
        }
        @JsonTypeName("long")
        public static record Long(long value) implements Comparable<Long> {
            public int compareWith(Long right) {
                return java.lang.Long.compare(value, right.value);
            }
        }
        @JsonTypeName("double")
        public static record Double(double value) implements Comparable<Double> {
            public int compareWith(Double right) {
                return java.lang.Double.compare(value, right.value);
            }
        }
    }

    public record ColumnSpec(String name, ColumnType type) {
        @Override
        public String toString() {
            return "ColumnSpec[name=" + name + " type=" + type + "]";
        }
    }

    public static enum Comparison {
        EQUALS,
        LESS_THAN,
        GREATER_THAN;

        public boolean matchesNonNull(Value left, Value right) {
            int cmp = left.compareAnyWithAny(right);

            return switch (this) {
                case EQUALS -> cmp == 0;
                case LESS_THAN -> cmp < 0;
                case GREATER_THAN -> cmp > 0;
            };
        }
    }
}

