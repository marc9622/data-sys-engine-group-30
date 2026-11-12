package dk.itu.datasys;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dk.itu.datasys.StorageEngine.Catalog;
import dk.itu.datasys.StorageEngine.ColumnStats;
import dk.itu.datasys.ops.FilterOperator;
import dk.itu.datasys.ops.LimitOperator;
import dk.itu.datasys.ops.Operator;
import dk.itu.datasys.ops.ProjectOperator;
import dk.itu.datasys.ops.ScanOperator;

public final class Planner {
    private static final Logger LOGGER = LoggerFactory.getLogger(Planner.class);

    private final StorageEngine engine;

    public Planner(StorageEngine engine) {
        this.engine = Objects.requireNonNull(engine, "engine");
    }

    public Plan plan(Catalog catalog, BoundStatement.Select select) {
        Objects.requireNonNull(select, "select");

        int tableIndex = select.tableIndex();
        List<StorageEngine.PartitionMeta> allPartitions = engine.activePartitions(catalog, tableIndex);
        List<StorageEngine.PartitionMeta> survivingPartitions = new ArrayList<>();

        if (select.where().isEmpty()) {
            survivingPartitions.addAll(allPartitions);

            LOGGER.debug("table={} no-where partitions={} decision=READ", tableIndex, survivingPartitions.size());
        }
        else {
            BoundStatement.Predicate predicate = select.where().get();

            for (int partitionNumber = 0; partitionNumber < allPartitions.size(); partitionNumber++) {
                StorageEngine.PartitionMeta partition = allPartitions.get(partitionNumber);
                boolean canMatch = StorageEngine.partitionMayContain(
                        partition.stats.get(predicate.columnIndex()),
                        predicate.comparison(),
                        predicate.constant());

                ColumnStats stats = partition.stats.get(predicate.columnIndex());
                LOGGER.debug("table={} column={} comparison={} const={} partition={} min={} max={} decision={}",
                        tableIndex, predicate.columnIndex(), predicate.comparison(), predicate.constant(),
                        partitionNumber, stats.min(), stats.max(),
                        canMatch ? "READ" : "PRUNED");

                if (canMatch)
                    survivingPartitions.add(partition);
            }
        }

        Operator root = new ScanOperator(engine, catalog, tableIndex, survivingPartitions);

        if (select.where().isPresent()) {
            BoundStatement.Predicate predicate = select.where().get();
            root = new FilterOperator(root, predicate);

            LOGGER.debug("added filter operation where={}", predicate);
        }

        if (select.columnIndices().isPresent()) {
            root = new ProjectOperator(root, select.columnIndices().get());

            String columns = select.columnIndices().stream().map(Object::toString).reduce("", (a, b) -> a + " " + b);
            LOGGER.debug("added projection operation columns=[{}]", columns);
        }

        if (select.limit().isPresent()) {
            root = new LimitOperator(root, select.limit().get());

            LOGGER.debug("added limit operation limit={}", select.limit().get());
        }

        int total = allPartitions.size();
        StorageEngine.ScanStats stats = new StorageEngine.ScanStats(
                total, survivingPartitions.size(), total - survivingPartitions.size());

        LOGGER.debug("created plan table={} totalPartitions={} survivingPartitions={} prunedPartitions={}",
                tableIndex, stats.partitionsTotal(), stats.partitionsRead(), stats.partitionsPruned());

        return new Plan(root, stats);
    }
}
