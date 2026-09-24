package dk.itu.datasys.ops;

import java.util.List;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dk.itu.datasys.Spec.ColumnSpec;
import dk.itu.datasys.Spec.ColumnType;
import dk.itu.datasys.Statement.Select.Predicate;

public final class FilterOperator extends Operator.Intermediate {
    private static final Logger LOGGER = LoggerFactory.getLogger(FilterOperator.class);

    private final Predicate predicate;
    private final int columnIndex;
    private final ColumnType columnType;
    private int rowsIn;
    private int rowsOut;

    public FilterOperator(Operator child, Predicate predicate, int columnIndex, ColumnType columnType) {
        super(child);
        this.predicate = Objects.requireNonNull(predicate);
        this.columnType = Objects.requireNonNull(columnType);
        if (columnIndex < 0)
            throw new IllegalArgumentException("column index must be non-negative");
        this.columnIndex = columnIndex;
    }

    @Override
    protected void openIntermediate() {
        rowsIn = 0;
        rowsOut = 0;
    }

    @Override
    public List<ColumnSpec> schema() {
        return childSchema();
    }

    @Override
    protected Object[] nextIntermediate() {
        Object[] row = childNext(); 
        while (row!= null) {
            rowsIn++;
            if (predicate.matches(row[columnIndex], columnType)) {
                rowsOut++;
                return row;
            }
            row = childNext();
        }
        return null;
    }

    @Override
    protected void closeIntermediate() {
        LOGGER.debug("column={} comparison={} constant={} rowsIn={} rowsOut={}",
                predicate.columnName(), predicate.comparison(), predicate.constant(), rowsIn, rowsOut);
    }
}
