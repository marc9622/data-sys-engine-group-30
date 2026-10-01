package dk.itu.datasys.ops;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dk.itu.datasys.Spec.ColumnSpec;

public final class DistinctOperator extends Operator.RowWiseIntermediate {
    private static final Logger LOGGER = LoggerFactory.getLogger(DistinctOperator.class);

    private final Set<Integer> seenRowHashes = new HashSet<>();
    private final Set<Integer> keyColumns;

    private int rowsTotal = 0;
    private int rowsPruned = 0;

    public DistinctOperator(Operator child, Set<Integer> keyColumns) {
        super(child);

        if (keyColumns.isEmpty())
            throw new IllegalArgumentException("keyColumns must be non-empty");
        this.keyColumns = keyColumns;
    }

    @Override
    protected void openIntermediate(List<ColumnSpec> schema) {
        seenRowHashes.clear();

        rowsTotal = 0;
        rowsPruned = 0;
    }

    @Override
    protected NextResult nextIntermediate(Object[] row) {
        rowsTotal++;

        int rowKey = Arrays.hashCode(keyColumns.stream().map(i -> row[i]).toArray());
        if (seenRowHashes.add(rowKey))
            return NextResult.of(row);

        rowsPruned++;

        return NextResult.retry;
    }

    @Override
    protected void closeIntermediate() {
        LOGGER.debug("rowsTotal={} rowsPruned={}", rowsTotal, rowsPruned);
    }
}
