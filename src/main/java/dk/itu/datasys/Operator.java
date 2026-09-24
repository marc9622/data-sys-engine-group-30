package dk.itu.datasys;

public interface Operator {
    void open();
    Object[] next();   // one row in schema column order, or null when exhausted
    void close();
}
