package dk.itu.datasys.ops;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import dk.itu.datasys.Spec.ColumnSpec;

public class AssertOperator extends Operator.Intermediate {
    private final List<Object[]> rowsExpected;
    private int rowsCurrent = 0;
    private boolean isOpen = false;

    public AssertOperator(Operator actual, List<Object[]> rowsExpected) {
        super(actual);
        this.rowsExpected = Objects.requireNonNull(rowsExpected);
    }

    @Override
    protected void openIntermediate() {
        isOpen = true;
        rowsCurrent = 0;
    }

    @Override
    public List<ColumnSpec> schema() {
        return childSchema();
    }

    @Override
    protected Object[] nextIntermediate() {
        assertIsOpen();

        Object[] rowActual = childNext();
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
    protected void closeIntermediate() {
        assertIsOpen();
        isOpen = false;
        if (rowsCurrent < rowsExpected.size())
            throw new AssertionError("Fewer rows (" + rowsCurrent + ") returned than expected (" + rowsExpected.size() + ")");
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
