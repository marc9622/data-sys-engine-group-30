package dk.itu.datasys.ops;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import dk.itu.datasys.Spec.ColumnSpec;

public final class AssertOperator implements Operator {

    private final Operator actual;
    private final List<Object[]> rowsExpected;
    private int rowsCurrent = 0;
    private boolean isOpen = false;

    public AssertOperator(Operator actual, List<Object[]> rowsExpected) {
        this.actual = actual;
        this.rowsExpected = Objects.requireNonNull(rowsExpected);
    }

    @Override
    public void open() {
        actual.open();

        isOpen = true;
        rowsCurrent = 0;
    }

    @Override
    public List<ColumnSpec> schema() {
        return actual.schema();
    }

    @Override
    public Object[] next() {
        assertIsOpen();

        Object[] rowActual = actual.next();
        if (rowActual == null) {
            if (rowsCurrent != rowsExpected.size())
                throw new AssertionError("Fewer rows (" + rowsCurrent + ") returned than expected (" + rowsExpected.size() + ")");

            return null;
        }

        if (rowsCurrent == rowsExpected.size())
            throw new AssertionError("More rows (" + rowsCurrent + ") returned than expected (" + rowsExpected.size() + ")");

        Object[] rowExpected = rowsExpected.get(rowsCurrent++);
        if (!Arrays.equals(rowActual, rowExpected))
            throw new AssertionError("Row " + (rowsCurrent - 1) + " does not match expected row. Expected: " + Arrays.toString(rowExpected) + ", Actual: " + Arrays.toString(rowActual));

        return rowActual;
    }

    @Override
    public void close() {
        assertIsOpen();
        isOpen = false;
        if (rowsCurrent < rowsExpected.size())
            throw new AssertionError("Fewer rows (" + rowsCurrent + ") returned than expected (" + rowsExpected.size() + ")");

        actual.close();
    }

    private void assertIsOpen() {
        if (!isOpen) {
            throw new IllegalStateException("Operator is not open. Call open() before calling next() or close().");
        }
    }

    public final List<ColumnSpec> openExhaustCloseAndGetSchema() {
        open();
        List<ColumnSpec> columns = schema();
        while (next() != null) {}
        close();
        return columns;
    }
}
