package dk.itu.datasys.ops;

import java.util.List;

import dk.itu.datasys.Spec.ColumnSpec;

public final class MockOperator implements Operator {
    private final List<ColumnSpec> columns;
    private final List<Object[]> rows;
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
    public Object[] next() {
        if (rowsCurrent == rows.size())
            return null;

        return rows.get(rowsCurrent++);
    }

    @Override
    public void close() {
        // No-op
    }
}

