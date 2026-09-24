package dk.itu.datasys;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

public class TestOperator extends IntermediateOperator {
    private final List<Object[]> rowsExpected;
    private int rowsCurrent;

    public TestOperator(Operator actual, List<Object[]> rowsExpected) {
        super(actual);
        this.rowsExpected = Objects.requireNonNull(rowsExpected);
    }

    @Override
    protected void openIntermediate() {
        // No-op
    }

    @Override
    protected Object[] nextIntermediate(Supplier<Object[]> next) {
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
        // No-op
    }
}
