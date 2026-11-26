package dk.itu.datasys.ops;

import static dk.itu.datasys.TestingUtils.*;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dk.itu.datasys.Spec.*;
import dk.itu.datasys.Statement.FilterPredicate;
import dk.itu.datasys.StorageEngine;
import dk.itu.datasys.StorageEngine.Catalog;
import dk.itu.datasys.TestingUtils.Streams;

class OperatorTest {

    @Test
    void mockOperator() {
        MockOperator mockOp = new MockOperator(tripsColumns, tripsRows);

        AssertOperator assertOp = new AssertOperator(mockOp, tripsRows);

        List<ColumnSpec> columns = assertOp.openExhaustCloseAndGetSchema();
        assertEquals(tripsColumns, columns);
    }

    @Test
    void scanOperatorReturnsRowsFromSelectedPartitions(@TempDir Path tempDir) {
        StorageEngine engine = createPartitionedEngine(tempDir);
        Catalog catalog = engine.viewCatalogForTesting();

        List<StorageEngine.PartitionMeta> partitions = engine.activePartitions(catalog, tripsName);
        List<StorageEngine.PartitionMeta> selectedPartitions = partitions.subList(1, 3);

        // Choose rows 2,3,4,5 for assertion(rows in partiotion 1 and 2) 
        List<Object[]> selectedRows = tripsRows.subList(2, 6);

        ScanOperator scanOp = new ScanOperator(engine, catalog, tripsName, selectedPartitions);
        AssertOperator assertOp = new AssertOperator(scanOp, selectedRows);

        assertOp.openExhaustCloseAndGetSchema();
    }

    @Test
    void scanOperatorReturnsNoRowsForEmptyPartitionList(@TempDir Path tempDir) {
        StorageEngine engine = createPartitionedEngine(tempDir);
        Catalog catalog = engine.viewCatalogForTesting();

        ScanOperator scanOp = new ScanOperator(engine, catalog, tripsName, List.of());
        AssertOperator assertOp = new AssertOperator(scanOp, List.of());

        assertOp.openExhaustCloseAndGetSchema();
    }

    @Test
    void scanOperatorReturnsTableSchema(@TempDir Path tempDir) {
        StorageEngine engine = createPartitionedEngine(tempDir);
        ScanOperator scanOp = new ScanOperator(engine, engine.viewCatalogForTesting(), tripsName, List.of());

        assertEquals(tripsColumns, scanOp.schema());
    }

    private StorageEngine createPartitionedEngine(Path tempDir) {
        //four partitions with two rows per partition
        String previousMaxRowsPerPartition = System.getProperty("maxRowsPerPartition");
        System.setProperty("maxRowsPerPartition", "2");

        try {
            StorageEngine engine = new StorageEngine(tempDir);
            createTableWithCatalog(engine, tripsName, tripsColumns);
            copyFromCsvFileWithCatalog(engine, tripsName, resource("trips.csv"));
            return engine;
        } finally {
            if (previousMaxRowsPerPartition == null)
                System.clearProperty("maxRowsPerPartition");
            else
                System.setProperty("maxRowsPerPartition", previousMaxRowsPerPartition);
        }
    }

    @Test
    void filterOperator() {
        MockOperator mockOp = new MockOperator(tripsColumns, tripsRows);

        for (int columnIndex = 0; columnIndex < tripsColumns.size(); columnIndex++) {
            ColumnSpec column = tripsColumns.get(columnIndex);

            Object constant = tripsRows.get(5)[columnIndex];
            FilterPredicate predicate = new FilterPredicate(column.name(), Comparison.EQUALS, constant);
            FilterOperator filterOp = new FilterOperator(mockOp, predicate, columnIndex);

            int i = columnIndex;
            List<Object[]> filteredRows = tripsRows.stream().filter(row -> row[i].equals(constant)).toList();
            AssertOperator assertOp = new AssertOperator(filterOp, filteredRows);

            List<ColumnSpec> columns = assertOp.openExhaustCloseAndGetSchema();
            assertEquals(tripsColumns, columns);
        }
    }

    @Test
    void limitOperator() {
        MockOperator mockOp = new MockOperator(tripsColumns, tripsRows);

        for (int limitCount : List.of(0, 5, tripsRows.size())) {
            LimitOperator limitOp = new LimitOperator(mockOp, limitCount);

            List<Object[]> limitedRows = tripsRows.stream().limit(limitCount).toList();
            AssertOperator assertOp = new AssertOperator(limitOp, limitedRows);

            List<ColumnSpec> columns = assertOp.openExhaustCloseAndGetSchema();
            assertEquals(tripsColumns, columns);
        }
    }

    @Test
    void projectOperator() {
        MockOperator mockOp = new MockOperator(tripsColumns, tripsRows);

        for (int columnIndex = 0; columnIndex < tripsColumns.size(); columnIndex++) {
            ColumnSpec column = tripsColumns.get(columnIndex);

            List<ColumnSpec> projection = List.of(column);
            ProjectOperator projectOp = new ProjectOperator(mockOp, projection.stream().map(ColumnSpec::name).toList());

            int i = columnIndex;
            List<Object[]> projectedRows = tripsRows.stream().map(row -> new Object[] {row[i]}).toList();
            AssertOperator assertOp = new AssertOperator(projectOp, projectedRows);

            List<ColumnSpec> columns = assertOp.openExhaustCloseAndGetSchema();
            assertEquals(projection, columns);
        }
    }

    @Test
    void distinctOperator() {
        List<Object[]> duplicatedRows = new ArrayList<>(tripsRows.size() * 2);
        duplicatedRows.addAll(tripsRows);
        duplicatedRows.addAll(tripsRows);

        MockOperator mockOp = new MockOperator(tripsColumns, duplicatedRows);

        List<List<Integer>> mappings = Streams
            .powerSetOf(IntStream.range(0, tripsColumns.size()).boxed().toList())
            .filter(set -> !set.isEmpty())
            .flatMap(subset -> Streams.permutationsOf(subset))
            .toList();

        for (List<Integer> columns : mappings) {
            DistinctOperator distinctOp = new DistinctOperator(mockOp, new HashSet<>(columns));

            class Wrapper {
                final Object[] row;

                Wrapper(Object[] row) {
                    this.row = row;
                }

                @Override
                public boolean equals(Object obj) {
                    if (this == obj) return true;
                    if (obj == null || getClass() != obj.getClass()) return false;

                    Wrapper other = (Wrapper) obj;
                    for (int columnIndex : columns) {
                        if (!Objects.equals(this.row[columnIndex], other.row[columnIndex]))
                            return false;
                    }
                    return true;
                }

                @Override
                public int hashCode() {
                    return Arrays.hashCode(columns.stream().map(i -> row[i]).toArray());
                }
            }

            List<Object[]> distinctedRows = tripsRows.stream()
                .map(Wrapper::new)
                .distinct()
                .map(w -> w.row)
                .toList();

            AssertOperator assertOp = new AssertOperator(distinctOp, distinctedRows);

            List<ColumnSpec> columnsSchema = assertOp.openExhaustCloseAndGetSchema();
            assertEquals(tripsColumns, columnsSchema);
        }
    }

    @Test
    void sortOperator() {
        MockOperator mockOp = new MockOperator(tripsColumns, tripsRows);

        for (int columnIndex = 0; columnIndex < tripsColumns.size(); columnIndex++) {
            for (SortOperator.Ordering ordering : SortOperator.Ordering.values()) {
                SortOperator sortOp = new SortOperator(mockOp, columnIndex, ordering);

                int i = columnIndex;
                List<Object[]> sortedRows = tripsRows.stream()
                    .sorted((left, right) ->
                        ordering.compareNonNull(left[i], right[i], tripsColumns.get(i).type())
                    )
                    .toList();

                AssertOperator assertOp = new AssertOperator(sortOp, sortedRows);

                List<ColumnSpec> columnsSchema = assertOp.openExhaustCloseAndGetSchema();
                assertEquals(tripsColumns, columnsSchema);
            }
        }
    }

    @Test
    void countOperator() {
        MockOperator mockOp = new MockOperator(tripsColumns, tripsRows);

        for (int count : List.of(0, 5, tripsRows.size())) {
            LimitOperator limitOp = new LimitOperator(mockOp, count);

            CountOperator countOp = new CountOperator(limitOp);

            List<Object[]> countRows = List.<Object[]>of(new Object[] {(long) count});
            AssertOperator assertOp = new AssertOperator(countOp, countRows);

            List<ColumnSpec> countSchema = List.of(new ColumnSpec(CountOperator.COLUMN_NAME, ColumnType.LONG));
            List<ColumnSpec> columnsSchema = assertOp.openExhaustCloseAndGetSchema();
            assertEquals(countSchema, columnsSchema);
        }
    }

    @Test
    void hashJoinOperatorDropsUnmatchedRows() {
        List<ColumnSpec> customersSchema = List.of(
                new ColumnSpec("c_name", ColumnType.STRING),
                new ColumnSpec("c_city", ColumnType.STRING));
        List<ColumnSpec> ordersSchema = List.of(
                new ColumnSpec("o_custname", ColumnType.STRING),
                new ColumnSpec("o_prodname", ColumnType.STRING));

        List<Object[]> customersRows = List.of(
                new Object[] {"Anna", "Copenhagen"},
                new Object[] {"Bob", "Aarhus"},
                new Object[] {"Carol", "Copenhagen"});

        List<Object[]> ordersRows = List.of(
                new Object[] {"Anna", "Bike"},
                new Object[] {"Bob", "Helmet"});

        MockOperator buildOp = new MockOperator(customersSchema, customersRows);
        MockOperator probeOp = new MockOperator(ordersSchema, ordersRows);

        HashJoinOperator joinOp = new HashJoinOperator(buildOp, probeOp, 0, 0);

        List<ColumnSpec> expectedSchema = new ArrayList<>(customersSchema);
        expectedSchema.addAll(ordersSchema);

        List<Object[]> expectedRows = List.of(
                new Object[] {"Anna", "Copenhagen", "Anna", "Bike"},
                new Object[] {"Bob", "Aarhus", "Bob", "Helmet"});

        AssertOperator assertOp = new AssertOperator(joinOp, expectedRows);
        List<ColumnSpec> resultSchema = assertOp.openExhaustCloseAndGetSchema();
        assertEquals(expectedSchema, resultSchema);
    }

    @Test
    void hashJoinOperatorMultipliesDuplicateKeys() {
        List<ColumnSpec> customersSchema = List.of(new ColumnSpec("c_name", ColumnType.STRING));
        List<ColumnSpec> ordersSchema = List.of(new ColumnSpec("o_custname", ColumnType.STRING));

        List<Object[]> customersRows = List.of(
                new Object[] {"Anna"},
                new Object[] {"Anna"});

        List<Object[]> ordersRows = List.of(
                new Object[] {"Anna"},
                new Object[] {"Anna"},
                new Object[] {"Anna"});

        MockOperator buildOp = new MockOperator(customersSchema, customersRows);
        MockOperator probeOp = new MockOperator(ordersSchema, ordersRows);

        HashJoinOperator joinOp = new HashJoinOperator(buildOp, probeOp, 0, 0);

        List<Object[]> expectedRows = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 2; j++) {
                expectedRows.add(new Object[] {"Anna", "Anna"});
            }
        }

        AssertOperator assertOp = new AssertOperator(joinOp, expectedRows);
        assertOp.openExhaustCloseAndGetSchema();
    }

    @Test
    void hashJoinOperatorEmptyBuildSideProducesNoRows() {
        List<ColumnSpec> customersSchema = List.of(new ColumnSpec("c_name", ColumnType.STRING));
        List<ColumnSpec> ordersSchema = List.of(new ColumnSpec("o_custname", ColumnType.STRING));

        List<Object[]> customersRows = List.of();

        List<Object[]> ordersRows = List.of(
                new Object[] {"Anna"},
                new Object[] {"Bob"},
                new Object[] {"Carol"});

        MockOperator buildOp = new MockOperator(customersSchema, customersRows);
        MockOperator probeOp = new MockOperator(ordersSchema, ordersRows);

        HashJoinOperator joinOp = new HashJoinOperator(buildOp, probeOp, 0, 0);

        AssertOperator assertOp = new AssertOperator(joinOp, List.of());
        assertOp.openExhaustCloseAndGetSchema();
    }

    @Test
    void hashJoinOperatorEmptyProbeSideProducesNoRows() {
        List<ColumnSpec> customersSchema = List.of(new ColumnSpec("c_name", ColumnType.STRING));
        List<ColumnSpec> ordersSchema = List.of(new ColumnSpec("o_custname", ColumnType.STRING));

        List<Object[]> customersRows = List.of(
                new Object[] {"Anna"},
                new Object[] {"Bob"});

        List<Object[]> ordersRows = List.of();

        MockOperator buildOp = new MockOperator(customersSchema, customersRows);
        MockOperator probeOp = new MockOperator(ordersSchema, ordersRows);

        HashJoinOperator joinOp = new HashJoinOperator(buildOp, probeOp, 0, 0);

        AssertOperator assertOp = new AssertOperator(joinOp, List.of());
        assertOp.openExhaustCloseAndGetSchema();
    }

    @Test
    void hashJoinOperatorCursorHandlesConsecutiveMatchesCorrectly() {
        List<ColumnSpec> customersSchema = List.of(new ColumnSpec("c_name", ColumnType.STRING));
        List<ColumnSpec> ordersSchema = List.of(new ColumnSpec("o_custname", ColumnType.STRING));

        List<Object[]> customersRows = List.of(
                new Object[] {"Anna"},
                new Object[] {"Anna"});

        List<Object[]> ordersRows = List.of(
                new Object[] {"Anna"},
                new Object[] {"Bob"});

        MockOperator buildOp = new MockOperator(customersSchema, customersRows);
        MockOperator probeOp = new MockOperator(ordersSchema, ordersRows);

        HashJoinOperator joinOp = new HashJoinOperator(buildOp, probeOp, 0, 0);

        List<Object[]> expectedRows = List.of(
                new Object[] {"Anna", "Anna"},
                new Object[] {"Anna", "Anna"});

        AssertOperator assertOp = new AssertOperator(joinOp, expectedRows);
        assertOp.openExhaustCloseAndGetSchema();
    }
}
