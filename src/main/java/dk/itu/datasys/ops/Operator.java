package dk.itu.datasys.ops;

import java.util.Objects;
import java.util.function.Supplier;

public sealed interface Operator permits Operator.Intermediate, ScanOperator {

    /**
     * Intializes or resets the operator's internal state.
     * Must be called before next() is called.
     */
    void open();

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

        @Override
        public final void open() {
            openIntermediate();
            child.open();
        }

        /**
         * Intializes or resets the operator's internal state.
         * Must be called before next() is called.
         */
        protected abstract void openIntermediate();

        @Override
        public final Object[] next() {
            return nextIntermediate(child::next);
        }

        /**
         * @return The next row in schema column order, or null when exhausted
         */
        protected abstract Object[] nextIntermediate(Supplier<Object[]> next);

        @Override
        public final void close() {
            closeIntermediate();
            child.close();
        }

        /**
         * Frees any resources held by the operator.
         * Must be called after next() returns null.
         */
        protected abstract void closeIntermediate();
    }
}
