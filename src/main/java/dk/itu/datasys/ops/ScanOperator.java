package dk.itu.datasys.ops;

import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dk.itu.datasys.StorageEngine;

public final class ScanOperator implements Operator {
    private static final Logger LOGGER = LoggerFactory.getLogger(ScanOperator.class);

    private final StorageEngine engine;
    private final String tableName;
    private final List<StorageEngine.PartitionMeta> partitions;
    private int nextPartitionIndex;
    private int rowsOut;
    private Iterator<Object[]> rows;

    public ScanOperator(StorageEngine engine, String tableName, List<StorageEngine.PartitionMeta> partitions) {
        this.engine = Objects.requireNonNull(engine);
        this.tableName = Objects.requireNonNull(tableName);
        this.partitions = List.copyOf(partitions);
    }

    @Override
    public void open() {
        nextPartitionIndex = 0;
        rowsOut = 0;
        rows = Collections.emptyIterator();
    }

    @Override
    public Object[] next() {
        while (!rows.hasNext()) {
            if (nextPartitionIndex >= partitions.size()) {
                return null;
            }

            StorageEngine.PartitionMeta partition = partitions.get(nextPartitionIndex++);

            rows = engine.readPartitionRows(tableName, partition).iterator();
        }

        return rows.next();
    }

    @Override
    public void close() {
        LOGGER.debug("table={} partitions={} rowsOut={}", tableName, partitions.size(), rowsOut);
    }

}
