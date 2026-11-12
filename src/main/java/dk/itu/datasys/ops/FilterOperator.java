package dk.itu.datasys.ops;

import java.util.List;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dk.itu.datasys.Spec.*;
import dk.itu.datasys.BoundStatement.Predicate;

public final class FilterOperator extends Operator.RowWiseIntermediate {
    private static final Logger LOGGER = LoggerFactory.getLogger(FilterOperator.class);

    private final Predicate predicate;
    private int rowsIn;
    private int rowsOut;

    public FilterOperator(Operator child, Predicate predicate) {
        super(child);
        this.predicate = Objects.requireNonNull(predicate);

        if (predicate.columnIndex() < 0) {
            LOGGER.error("column index not non-negative columnIndex={}", predicate.columnIndex());
            throw new IllegalArgumentException("column index must be non-negative");
        }
    }

    @Override
    protected void openIntermediate(List<ColumnSpec> schema) {
        rowsIn = 0;
        rowsOut = 0;
    }

    @Override
    protected NextResult nextIntermediate(Value[] row) {
        rowsIn++;
        if (predicate.matchesNonNull(row[predicate.columnIndex()])) {
            rowsOut++;
            return NextResult.of(row);
        }

        return NextResult.retry;
    }

    @Override
    protected void closeIntermediate() {
        LOGGER.debug("column={} comparison={} constant={} rowsIn={} rowsOut={}",
                predicate.columnIndex(), predicate.comparison(), predicate.constant(), rowsIn, rowsOut);
    }
}
