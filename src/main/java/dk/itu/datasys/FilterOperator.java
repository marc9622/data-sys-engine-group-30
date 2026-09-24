package dk.itu.datasys;

import java.util.Objects;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dk.itu.datasys.Spec.ColumnType;
import dk.itu.datasys.Statement.Select.Predicate;

public final class FilterOperator extends IntermediateOperator {
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
    public void openIntermediate() {
        rowsIn = 0;
        rowsOut = 0;
    }

    @Override
    public Object[] nextIntermediate(Supplier<Object[]> next) {
        Object[] row = next(); 
        while (row!= null) {
            rowsIn++;
            if (predicate.matches(row[columnIndex], columnType)) {
                rowsOut++;
                return row;
            }
            row = next();
        }
        return null;
    }

    @Override
    public void closeIntermediate() {
        LOGGER.debug("column={} comparison={} constant={} rowsIn={} rowsOut={}",
                predicate.columnName(), predicate.comparison(), predicate.constant(), rowsIn, rowsOut);
    }

}
