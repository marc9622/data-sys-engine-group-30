package dk.itu.datasys.ops;

import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
    public void openIntermediate() {
        rowsCurrent = 0;
    }

    @Override
    public Object[] nextIntermediate(Supplier<Object[]> next) {
        if (rowsCurrent >= rowsMax) {
            return null;
        }
        rowsCurrent++;
        return next();
    }

    @Override
    public void closeIntermediate() {
        LOGGER.debug("rowsMax={} rowsCurrent={}", rowsMax, rowsCurrent);
    }
}
