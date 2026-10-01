package dk.itu.datasys.ops;

import java.util.List;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dk.itu.datasys.Spec.*;

public final class SortOperator extends Operator.ExhaustIntermediate {
    private static final Logger LOGGER = LoggerFactory.getLogger(SortOperator.class);

    private final int column;
    private final Ordering ordering;

    public static enum Ordering {
        ASCENDING, DESCENDING;

        public int compareNonNull(Object left, Object right, ColumnType type) {
            Objects.requireNonNull(left);
            Objects.requireNonNull(right);

            int result = Comparison.compareNonNull(left, right, type);
            return switch (this) {
                case ASCENDING -> result;
                case DESCENDING -> -result;
            };
        }
    }

    public SortOperator(Operator child, int column, Ordering ordering) {
        super(child);
        this.column = column;
        this.ordering = ordering;
    }

    @Override
    protected List<Object[]> openAndExhaustIntermediate(List<ColumnSpec> schema, List<Object[]> rows) {
        ColumnType type = schema.get(column).type();

        return rows.stream()
            .sorted((l, r) -> ordering.compareNonNull(l[column], r[column], type))
            .toList();
    }

    @Override
    protected void closeIntermediate() {
        LOGGER.debug("column={} ordering={}", column, ordering);
    }
}
