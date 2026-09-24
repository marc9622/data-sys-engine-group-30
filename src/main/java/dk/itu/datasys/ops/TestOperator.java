package dk.itu.datasys.ops;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import dk.itu.datasys.Spec.ColumnSpec;

public class TestOperator extends Operator.Intermediate {
    private final List<Object[]> rowsExpected;
    private int rowsCurrent;
    private boolean isOpen = false;

    public TestOperator(Operator actual, List<Object[]> rowsExpected) {
        super(actual);
        this.rowsExpected = Objects.requireNonNull(rowsExpected);
    }

    @Override
    protected void openIntermediate() {
        isOpen = true;
    }

    @Override
    public List<ColumnSpec> schema() {
        return childSchema();
    }

    @Override
    protected Object[] nextIntermediate() {
        assertIsOpen();

        Object[] rowActual = next();
        if (rowsCurrent >= rowsExpected.size()) {
            throw new AssertionError("More rows (" + rowsCurrent + ") returned than expected (" + rowsExpected.size() + ")");
        }

        Object[] rowExpected = rowsExpected.get(rowsCurrent++);
        if (!Arrays.equals(rowActual, rowExpected)) {
            throw new AssertionError("Row " + (rowsCurrent - 1) + " does not match expected row. Expected: " + Arrays.toString(rowExpected) + ", Actual: " + Arrays.toString(rowActual));
        }

        return rowActual;
    }

    @Override
    protected void closeIntermediate() {
        assertIsOpen();
        isOpen = false;
    }

    private void assertIsOpen() {
        if (!isOpen) {
            throw new IllegalStateException("Operator is not open. Call open() before calling next() or close().");
        }
    }
}
