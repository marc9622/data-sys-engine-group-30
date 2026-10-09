package dk.itu.datasys.ops;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dk.itu.datasys.Spec.ColumnSpec;

public final class LimitOperator extends Operator.RowWiseIntermediate {
    private static final Logger LOGGER = LoggerFactory.getLogger(FilterOperator.class);

    private final int rowsMax;
    private int rowsCurrent;

    public LimitOperator(Operator child, int rowsMax) {
        super(child);
        this.rowsMax = rowsMax;
        if (rowsMax < 0) {
            LOGGER.error("limit not non-negative rowsMax={}", rowsMax);
            throw new IllegalArgumentException("rowsMax must be non-negative");
        }
    }

    @Override
    protected void openIntermediate(List<ColumnSpec> schema) {
        rowsCurrent = 0;
    }

    @Override
    protected NextResult nextIntermediate(Object[] row) {
        if (rowsCurrent >= rowsMax)
            return NextResult.exhausted;

        rowsCurrent++;
        return NextResult.of(row);
    }

    @Override
    protected void closeIntermediate() {
        LOGGER.debug("rowsMax={} rowsCurrent={}", rowsMax, rowsCurrent);
    }
}
