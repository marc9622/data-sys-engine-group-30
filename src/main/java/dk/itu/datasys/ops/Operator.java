package dk.itu.datasys.ops;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import dk.itu.datasys.Spec.ColumnSpec;
import dk.itu.datasys.StorageEngine;
import dk.itu.datasys.StorageEngine.Row;

public interface Operator {

    /**
     * Intializes or resets the operator's internal state.
     * Must be called before next() is called.
     */
    void open();

    /**
     * @return The schema of the operator's output rows, in column order.
     */
    List<ColumnSpec> schema();

    /**
     * @return The next row in schema column order, or null when exhausted
     */
    Row next();

    /**
     * Frees any resources held by the operator.
     * Must be called after next() returns null.
     */
    void close();


    default List<Row> exhaust() {
        List<Row> rows = new ArrayList<>();
        exhaust(rows);
        return rows;
    }

    default void exhaust(List<Row> rows) {
        for (Row row; (row = next()) != null;)
            rows.add(row);
    }

    public static abstract class RowWiseIntermediate implements Operator {
        private final Operator child;

        protected RowWiseIntermediate(Operator child) {
            this.child = Objects.requireNonNull(child);
        }

        @Override
        public final void open() {
            child.open();
            openIntermediate(child.schema());
        }

        protected abstract void openIntermediate(List<ColumnSpec> childSchema);

        @Override
        public List<ColumnSpec> schema() {
            return child.schema();
        }

        protected static sealed interface NextResult permits NextResult.Row, NextResult.Retry, NextResult.Exhausted {
            public static final record Row(StorageEngine.Row row) implements NextResult {}
            public static final class Retry implements NextResult {}
            public static final class Exhausted implements NextResult {}

            public static NextResult of(StorageEngine.Row row) { return new Row(row); }
            public static NextResult retry = new Retry();
            public static NextResult exhausted = new Exhausted();
        }

        @Override
        public final Row next() {
            while (true) {
                Row childRow = child.next();
                if (childRow == null)
                    return null;

                switch (nextIntermediate(childRow)) {
                    case NextResult.Row(StorageEngine.Row row) -> { return row; }
                    case NextResult.Retry _ -> { continue; }
                    case NextResult.Exhausted _ -> { return null; }
                }
            }
        }

        protected abstract NextResult nextIntermediate(Row childNext);

        @Override
        public final void close() {
            child.close();
            closeIntermediate();
        }

        protected abstract void closeIntermediate();
    }

    public static abstract class ExhaustIntermediate implements Operator {
        private final Operator child;

        private List<Row> rows;
        private int rowCurrent;

        protected ExhaustIntermediate(Operator child) {
            this.child = Objects.requireNonNull(child);
        }

        @Override
        public final void open() {
            child.open();
            rows = openAndExhaustIntermediate(child.schema(), child.exhaust());
            rowCurrent = 0;
        }

        protected abstract List<Row> openAndExhaustIntermediate(List<ColumnSpec> childSchema, List<Row> childRows);

        @Override
        public List<ColumnSpec> schema() {
            return child.schema();
        }

        @Override
        public final Row next() {
            if (rowCurrent == rows.size())
                return null;

            return rows.get(rowCurrent++);
        }

        @Override
        public final void close() {
            child.close();
            closeIntermediate();
        }

        protected abstract void closeIntermediate();
    }
}
