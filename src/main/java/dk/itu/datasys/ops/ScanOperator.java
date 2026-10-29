package dk.itu.datasys.ops;

import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dk.itu.datasys.Spec.ColumnSpec;
import dk.itu.datasys.StorageEngine;
import dk.itu.datasys.StorageEngine.Catalog;

public final class ScanOperator implements Operator {
    private static final Logger LOGGER = LoggerFactory.getLogger(ScanOperator.class);

    private final StorageEngine engine;
    private final Catalog catalog;
    private final String tableName;
    private final List<StorageEngine.PartitionMeta> partitions;
    private int nextPartitionIndex;
    private int rowsOut;
    private Iterator<Object[]> rows;

    public ScanOperator(StorageEngine engine, Catalog catalog, String tableName, List<StorageEngine.PartitionMeta> partitions) {
        this.engine = Objects.requireNonNull(engine);
        this.catalog = catalog;
        this.tableName = Objects.requireNonNull(tableName);
        this.partitions = List.copyOf(partitions);
    }

    @Override
    public List<ColumnSpec> schema() {
        return engine.schema(catalog, tableName);
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
            if (nextPartitionIndex >= partitions.size())
                return null;

            StorageEngine.PartitionMeta partition = partitions.get(nextPartitionIndex++);

            rows = engine.readPartitionRows(catalog, tableName, partition).iterator();
        }

        return rows.next();
    }

    @Override
    public void close() {
        LOGGER.debug("table={} partitions={} rowsOut={}", tableName, partitions.size(), rowsOut);
    }

}
