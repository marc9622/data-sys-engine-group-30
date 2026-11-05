package dk.itu.datasys.ops;

import java.util.List;

import dk.itu.datasys.Spec.ColumnSpec;
import dk.itu.datasys.StorageEngine;
import dk.itu.datasys.StorageEngine.Row;

public final class MockOperator implements Operator {
    private final List<ColumnSpec> columns;
    private final List<Object[]> rows;
    private static final String partitionFileName = StorageEngine.partitionFileName("mock", 0);
    private int rowsCurrent;

    public MockOperator(List<ColumnSpec> columns, List<Object[]> rows) {
        this.columns = columns;
        this.rows = rows;
        this.rowsCurrent = 0;
    }

    @Override
    public void open() {
        rowsCurrent = 0;
    }

    @Override
    public List<ColumnSpec> schema() {
        return columns;
    }

    @Override
    public Row next() {
        if (rowsCurrent == rows.size())
            return null;

        int index = rowsCurrent++;
        Object[] data = rows.get(index);
        return Row.of(data, partitionFileName, index);
    }

    @Override
    public void close() {
        // No-op
    }
}

