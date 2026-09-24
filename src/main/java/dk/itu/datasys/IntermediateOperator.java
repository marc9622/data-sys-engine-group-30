package dk.itu.datasys;

import java.util.Objects;
import java.util.function.Supplier;

public abstract class IntermediateOperator implements Operator {
    private final Operator child;

    protected IntermediateOperator(Operator child) {
        this.child = Objects.requireNonNull(child);
    }

    @Override
    public final void open() {
        openIntermediate();
        child.open();
    }

    protected abstract void openIntermediate();

    @Override
    public final Object[] next() {
        return nextIntermediate(child::next);
    }

    protected abstract Object[] nextIntermediate(Supplier<Object[]> next);

    @Override
    public final void close() {
        closeIntermediate();
        child.close();
    }

    protected abstract void closeIntermediate();
}
