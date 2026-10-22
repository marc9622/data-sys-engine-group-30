package dk.itu.datasys.ops;

import java.util.List;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dk.itu.datasys.Spec.ColumnSpec;
import dk.itu.datasys.Spec.ColumnType;
import dk.itu.datasys.Statement.Select.Predicate;

public final class FilterOperator extends Operator.RowWiseIntermediate {
    private static final Logger LOGGER = LoggerFactory.getLogger(FilterOperator.class);

    private final Predicate predicate;
    private final int columnIndex;
    private ColumnType columnType;
    private int rowsIn;
    private int rowsOut;

    public FilterOperator(Operator child, Predicate predicate, int columnIndex) {
        super(child);
        this.predicate = Objects.requireNonNull(predicate);

        if (columnIndex < 0) {
            LOGGER.error("column index not non-negative columnIndex={}", columnIndex);
            throw new IllegalArgumentException("column index must be non-negative");
        }
        this.columnIndex = columnIndex;
    }

    @Override
    protected void openIntermediate(List<ColumnSpec> schema) {
        rowsIn = 0;
        rowsOut = 0;
        columnType = schema.get(columnIndex).type();
    }

    @Override
    protected NextResult nextIntermediate(Object[] row) {
        rowsIn++;
        if (predicate.matches(row[columnIndex], columnType)) {
            rowsOut++;
            return NextResult.of(row);
        }

        return NextResult.retry;
    }

    @Override
    protected void closeIntermediate() {
        LOGGER.debug("column={} comparison={} constant={} rowsIn={} rowsOut={}",
                predicate.columnName(), predicate.comparison(), predicate.constant(), rowsIn, rowsOut);
    }
}
