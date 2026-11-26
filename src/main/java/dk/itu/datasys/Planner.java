package dk.itu.datasys;

import java.util.List;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dk.itu.datasys.Spec.*;
import dk.itu.datasys.StorageEngine.Catalog;
import dk.itu.datasys.ops.FilterOperator;
import dk.itu.datasys.ops.HashJoinOperator;
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

    /**
     * Plans one scan pipeline per table and folds tables in FROM order.
     * Only one predicate may connect each newly added table to the existing join.
     */
    public Plan plan(Catalog catalog, Statement.Select select) {
        Objects.requireNonNull(select, "select");
        ColumnResolver resolver = new ColumnResolver(catalog, select.tableNames());
        int partitionsTotal = 0;
        int partitionsRead = 0;
        Operator root = null;

        for (int tableIndex = 0; tableIndex < select.tableNames().size(); tableIndex++) {
            String tableName = select.tableNames().get(tableIndex);
            List<StorageEngine.PartitionMeta> allPartitions = engine.activePartitions(catalog, tableName);
            List<Statement.FilterPredicate> tableFilters = select.filters().stream()
                    .filter(filter -> resolver.resolve(filter.columnName()).tableName().equals(tableName))
                    .toList();
            List<StorageEngine.PartitionMeta> survivingPartitions = allPartitions.stream()
                    .filter(partition -> tableFilters.stream().allMatch(filter ->
                            partitionMayContain(partition, filter, resolver.resolve(filter.columnName()).tableColumnIndex(),
                                    resolver.resolve(filter.columnName()).column().type())))
                    .toList();

            partitionsTotal += allPartitions.size();
            partitionsRead += survivingPartitions.size();

            Operator tableRoot = new ScanOperator(engine, catalog, tableName, survivingPartitions);
            for (Statement.FilterPredicate filter : tableFilters) {
                int columnIndex = resolver.resolve(filter.columnName()).tableColumnIndex();
                tableRoot = new FilterOperator(tableRoot, filter, columnIndex);
                LOGGER.debug("added filter operation table={} where={}", tableName, filter);
            }

            if (root == null) {
                root = tableRoot;
                continue;
            }

            int currentTableIndex = tableIndex;
            List<Statement.JoinPredicate> connectingJoins = select.joins().stream()
                    .filter(join -> {
                        String leftTable = resolver.resolve(join.leftColumnName()).tableName();
                        String rightTable = resolver.resolve(join.rightColumnName()).tableName();
                        return (leftTable.equals(tableName) && select.tableNames().subList(0, currentTableIndex).contains(rightTable))
                                || (rightTable.equals(tableName) && select.tableNames().subList(0, currentTableIndex).contains(leftTable));
                    })
                    .toList();

            if (connectingJoins.isEmpty())
                throw new IllegalArgumentException("no join predicate connects table " + tableName
                        + " to the preceding tables; reorder the FROM list to place a connected table earlier");
            if (connectingJoins.size() > 1)
                throw new IllegalArgumentException("multiple join predicates connect table " + tableName
                        + "; only one connecting predicate per table is supported");

            Statement.JoinPredicate join = connectingJoins.getFirst();
            ColumnResolver.Resolution left = resolver.resolve(join.leftColumnName());
            ColumnResolver.Resolution right = resolver.resolve(join.rightColumnName());
            boolean leftIsNewTable = left.tableName().equals(tableName);
            ColumnResolver.Resolution buildKey = leftIsNewTable ? right : left;
            ColumnResolver.Resolution probeKey = leftIsNewTable ? left : right;

            root = new HashJoinOperator(root, tableRoot, buildKey.joinedColumnIndex(),
                    probeKey.tableColumnIndex());
            LOGGER.debug("added hash join table={} buildKey={} probeKey={}",
                    tableName, buildKey.column().name(), probeKey.column().name());
        }

        if (!select.columns().isEmpty()) {
            root = new ProjectOperator(root, select.columns());

            LOGGER.debug("added projection operation columns=[{}]", String.join(" ", select.columns()));
        }

        if (select.limit().isPresent()) {
            root = new LimitOperator(root, select.limit().get());

            LOGGER.debug("added limit operation limit={}", select.limit().get());
        }

        StorageEngine.ScanStats stats = new StorageEngine.ScanStats(
                partitionsTotal, partitionsRead, partitionsTotal - partitionsRead);

        LOGGER.debug("created plan tables=[{}] totalPartitions={} survivingPartitions={} prunedPartitions={}",
                String.join(" ", select.tableNames()), stats.partitionsTotal(),
                stats.partitionsRead(), stats.partitionsPruned());

        return new Plan(root, stats);
    }

    private static boolean partitionMayContain(StorageEngine.PartitionMeta partition,
                                               Statement.FilterPredicate filter,
                                               int columnIndex,
                                               ColumnType columnType) {
        return StorageEngine.partitionMayContain(partition.mins.get(columnIndex),
                partition.maxs.get(columnIndex), filter.comparison(), filter.constant(), columnType);
    }
}
