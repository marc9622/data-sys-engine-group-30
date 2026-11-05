package dk.itu.datasys.ops;

import java.util.List;
import java.util.Objects;

import dk.itu.datasys.Spec.ColumnSpec;
import dk.itu.datasys.StorageEngine.Row;

public final class AssertOperator implements Operator {

    private final Operator actual;
    private final List<Row> rowsExpected;
    private final boolean compareLocations;
    private int rowsCurrent = 0;
    private boolean isOpen = false;

    public AssertOperator(Operator actual, List<Object[]> rowsDataExpected) {
        List<Row> rowsExpected = rowsDataExpected.stream()
            .map(Row::ofEphemeral)
            .toList();

        this(actual, rowsExpected, false);
    }

    private AssertOperator(Operator actual, List<Row> rowsExpected, boolean compareLocations) {
        this.actual = actual;
        this.rowsExpected = Objects.requireNonNull(rowsExpected);
        this.compareLocations = compareLocations;
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
    public Row next() {
        assertIsOpen();

        Row rowActual = actual.next();
        if (rowActual == null) {
            if (rowsCurrent != rowsExpected.size())
                throw new AssertionError("Fewer rows (" + rowsCurrent + ") returned than expected (" + rowsExpected.size() + ")");

            return null;
        }

        if (rowsCurrent == rowsExpected.size())
            throw new AssertionError("More rows (" + rowsCurrent + ") returned than expected (" + rowsExpected.size() + ")");

        Row rowExpected = rowsExpected.get(rowsCurrent++);
        if (compareLocations) {
            if (!rowExpected.equals(rowActual))
                throw new AssertionError("Row " + (rowsCurrent - 1) + " does not match expected row. Expected: " + rowExpected + ", Actual: " + rowActual);
        }
        else {
            if (!rowExpected.dataEquals(rowActual)) {
                throw new AssertionError("Row " + (rowsCurrent - 1) + " does not match expected row. Expected: " + rowExpected.dataToArrayString() + ", Actual: " + rowActual.dataToArrayString());
            }
        }

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
