package dk.itu.datasys.ops;

import java.util.List;
import java.util.Objects;

import dk.itu.datasys.Spec.ColumnSpec;

public sealed interface Operator permits Operator.Intermediate, ScanOperator {

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
    Object[] next();

    /**
     * Frees any resources held by the operator.
     * Must be called after next() returns null.
     */
    void close();


    public static non-sealed abstract class Intermediate implements Operator {
        private final Operator child;

        protected Intermediate(Operator child) {
            this.child = Objects.requireNonNull(child);
        }


        protected final List<ColumnSpec> childSchema() {
            return child.schema();
        }

        protected final Object[] childNext() {
            return child.next();
        }


        @Override
        public final void open() {
            openIntermediate();
            child.open();
        }

        protected abstract void openIntermediate();

        @Override
        public final Object[] next() {
            return nextIntermediate();
        }

        protected abstract Object[] nextIntermediate();

        @Override
        public final void close() {
            closeIntermediate();
            child.close();
        }

        protected abstract void closeIntermediate();
    }
}
