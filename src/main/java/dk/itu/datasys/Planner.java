package dk.itu.datasys;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dk.itu.datasys.Spec.ColumnSpec;
import dk.itu.datasys.Spec.ColumnType;
import dk.itu.datasys.Statement.Select;
import dk.itu.datasys.ops.FilterOperator;
import dk.itu.datasys.ops.Operator;
import dk.itu.datasys.ops.ScanOperator;

public final class Planner {
    private static final Logger LOGGER = LoggerFactory.getLogger(Planner.class);

    private final StorageEngine engine;

    public Planner(StorageEngine engine) {
        this.engine = Objects.requireNonNull(engine, "engine");
    }

    public Plan plan(Select select) {
        Objects.requireNonNull(select, "select");

        String tableName = select.tableName();
        List<StorageEngine.PartitionMeta> allPartitions = engine.partitions(tableName);
        List<StorageEngine.PartitionMeta> survivingPartitions = new ArrayList<>();

        if (select.where().isEmpty()) {
            survivingPartitions.addAll(allPartitions);
        } else {
            Select.Predicate predicate = select.where().get();
            List<ColumnSpec> schema = engine.schema(tableName);
            int columnIndex = columnIndex(schema, predicate.columnName());
            ColumnType columnType = schema.get(columnIndex).type();

            for (int partitionNumber = 0; partitionNumber < allPartitions.size(); partitionNumber++) {
                StorageEngine.PartitionMeta partition = allPartitions.get(partitionNumber);
                boolean canMatch = StorageEngine.partitionMayContain(
                        partition.mins.get(columnIndex),
                        partition.maxs.get(columnIndex),
                        predicate.comparison(),
                        predicate.constant(),
                        columnType);

                LOGGER.debug("table={} column={} comparison={} const={} partition={} min={} max={} decision={}",
                        tableName, predicate.columnName(), predicate.comparison(), predicate.constant(),
                        partitionNumber, partition.mins.get(columnIndex), partition.maxs.get(columnIndex),
                        canMatch ? "READ" : "PRUNED");

                if (canMatch)
                    survivingPartitions.add(partition);
            }
        }

        Operator root = new ScanOperator(engine, tableName, survivingPartitions);
        if (select.where().isPresent()) {
            Select.Predicate predicate = select.where().get();
            List<ColumnSpec> schema = engine.schema(tableName);
            int columnIndex = columnIndex(schema, predicate.columnName());
            root = new FilterOperator(root, predicate, columnIndex, schema.get(columnIndex).type());
        }

        int total = allPartitions.size();
        StorageEngine.ScanStats stats = new StorageEngine.ScanStats(
                total, survivingPartitions.size(), total - survivingPartitions.size());
        return new Plan(root, stats);
    }

    private static int columnIndex(List<ColumnSpec> schema, String columnName) {
        for (int index = 0; index < schema.size(); index++) {
            if (schema.get(index).name().equals(columnName))
                return index;
        }
        throw new IllegalArgumentException("unknown column: " + columnName);
    }
}