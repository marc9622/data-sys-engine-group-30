package dk.itu.datasys.ops;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dk.itu.datasys.Spec.ColumnSpec;

public final class LimitOperator extends Operator.Intermediate {
    private static final Logger LOGGER = LoggerFactory.getLogger(FilterOperator.class);

    private final int rowsMax;
    private int rowsCurrent;

    public LimitOperator(Operator child, int rowsMax) {
        super(child);
        this.rowsMax = rowsMax;
        if (rowsMax < 0)
            throw new IllegalArgumentException("rowsMax must be non-negative");
    }

    @Override
    protected void openIntermediate() {
        rowsCurrent = 0;
    }

    @Override
    public List<ColumnSpec> schema() {
        return childSchema();
    }

    @Override
    protected Object[] nextIntermediate() {
        if (rowsCurrent >= rowsMax) {
            return null;
        }
        rowsCurrent++;
        return childNext();
    }

    @Override
    protected void closeIntermediate() {
        LOGGER.debug("rowsMax={} rowsCurrent={}", rowsMax, rowsCurrent);
    }
}
