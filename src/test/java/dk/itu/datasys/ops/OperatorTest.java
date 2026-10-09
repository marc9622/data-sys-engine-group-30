package dk.itu.datasys.ops;

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
import dk.itu.datasys.StorageEngine;
import dk.itu.datasys.Statement.Select.Predicate;
import dk.itu.datasys.Utils;

class OperatorTest {

    @Test
    void mockOperator() {
        MockOperator mockOp = new MockOperator(Utils.tripsColumns, Utils.tripsRows);

        AssertOperator assertOp = new AssertOperator(mockOp, Utils.tripsRows);

        List<ColumnSpec> columns = assertOp.openExhaustCloseAndGetSchema();
        assertEquals(Utils.tripsColumns, columns);
    }

    @Test
    void scanOperatorReturnsRowsFromSelectedPartitions(@TempDir Path tempDir) {
        StorageEngine engine = createPartitionedEngine(tempDir);

        List<StorageEngine.PartitionMeta> partitions = engine.partitions(Utils.tripsName);
        List<StorageEngine.PartitionMeta> selectedPartitions = partitions.subList(1, 3);

        // Choose rows 2,3,4,5 for assertion(rows in partiotion 1 and 2) 
        List<Object[]> selectedRows = Utils.tripsRows.subList(2, 6);

        ScanOperator scanOp = new ScanOperator(engine, Utils.tripsName, selectedPartitions);
        AssertOperator assertOp = new AssertOperator(scanOp, selectedRows);

        assertOp.openExhaustCloseAndGetSchema();
    }

    @Test
    void scanOperatorReturnsNoRowsForEmptyPartitionList(@TempDir Path tempDir) {
        StorageEngine engine = createPartitionedEngine(tempDir);

        ScanOperator scanOp = new ScanOperator(engine, Utils.tripsName, List.of());
        AssertOperator assertOp = new AssertOperator(scanOp, List.of());

        assertOp.openExhaustCloseAndGetSchema();
    }

    @Test
    void scanOperatorReturnsTableSchema(@TempDir Path tempDir) {
        StorageEngine engine = createPartitionedEngine(tempDir);
        ScanOperator scanOp = new ScanOperator(engine, Utils.tripsName, List.of());

        assertEquals(Utils.tripsColumns, scanOp.schema());
    }

    private StorageEngine createPartitionedEngine(Path tempDir) {
        //four partitions with two rows per partition
        String previousMaxRowsPerPartition = System.getProperty("maxRowsPerPartition");
        System.setProperty("maxRowsPerPartition", "2");

        try {
            StorageEngine engine = new StorageEngine(tempDir);
            engine.createTable(Utils.tripsName, Utils.tripsColumns);
            engine.copyFromCsvFile(Utils.tripsName, Utils.resource("trips.csv").toString());
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
        MockOperator mockOp = new MockOperator(Utils.tripsColumns, Utils.tripsRows);

        for (int columnIndex = 0; columnIndex < Utils.tripsColumns.size(); columnIndex++) {
            ColumnSpec column = Utils.tripsColumns.get(columnIndex);

            Object constant = Utils.tripsRows.get(5)[columnIndex];
            Predicate predicate = new Predicate(column.name(), Comparison.EQUALS, constant);
            FilterOperator filterOp = new FilterOperator(mockOp, predicate, columnIndex);

            int i = columnIndex;
            List<Object[]> filteredRows = Utils.tripsRows.stream().filter(row -> row[i].equals(constant)).toList();
            AssertOperator assertOp = new AssertOperator(filterOp, filteredRows);

            List<ColumnSpec> columns = assertOp.openExhaustCloseAndGetSchema();
            assertEquals(Utils.tripsColumns, columns);
        }
    }

    @Test
    void limitOperator() {
        MockOperator mockOp = new MockOperator(Utils.tripsColumns, Utils.tripsRows);

        for (int limitCount : List.of(0, 5, Utils.tripsRows.size())) {
            LimitOperator limitOp = new LimitOperator(mockOp, limitCount);

            List<Object[]> limitedRows = Utils.tripsRows.stream().limit(limitCount).toList();
            AssertOperator assertOp = new AssertOperator(limitOp, limitedRows);

            List<ColumnSpec> columns = assertOp.openExhaustCloseAndGetSchema();
            assertEquals(Utils.tripsColumns, columns);
        }
    }

    @Test
    void projectOperator() {
        MockOperator mockOp = new MockOperator(Utils.tripsColumns, Utils.tripsRows);

        for (int columnIndex = 0; columnIndex < Utils.tripsColumns.size(); columnIndex++) {
            ColumnSpec column = Utils.tripsColumns.get(columnIndex);

            List<ColumnSpec> projection = List.of(column);
            ProjectOperator projectOp = new ProjectOperator(mockOp, projection.stream().map(ColumnSpec::name).toList());

            int i = columnIndex;
            List<Object[]> projectedRows = Utils.tripsRows.stream().map(row -> new Object[] {row[i]}).toList();
            AssertOperator assertOp = new AssertOperator(projectOp, projectedRows);

            List<ColumnSpec> columns = assertOp.openExhaustCloseAndGetSchema();
            assertEquals(projection, columns);
        }
    }

    @Test
    void distinctOperator() {
        List<Object[]> duplicatedRows = new ArrayList<>(Utils.tripsRows.size() * 2);
        duplicatedRows.addAll(Utils.tripsRows);
        duplicatedRows.addAll(Utils.tripsRows);

        MockOperator mockOp = new MockOperator(Utils.tripsColumns, duplicatedRows);

        List<List<Integer>> mappings = Utils.Streams
            .powerSetOf(IntStream.range(0, Utils.tripsColumns.size()).boxed().toList())
            .filter(set -> !set.isEmpty())
            .flatMap(subset -> Utils.Streams.permutationsOf(subset))
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

            List<Object[]> distinctedRows = Utils.tripsRows.stream()
                .map(Wrapper::new)
                .distinct()
                .map(w -> w.row)
                .toList();

            AssertOperator assertOp = new AssertOperator(distinctOp, distinctedRows);

            List<ColumnSpec> columnsSchema = assertOp.openExhaustCloseAndGetSchema();
            assertEquals(Utils.tripsColumns, columnsSchema);
        }
    }

    @Test
    void sortOperator() {
        MockOperator mockOp = new MockOperator(Utils.tripsColumns, Utils.tripsRows);

        for (int columnIndex = 0; columnIndex < Utils.tripsColumns.size(); columnIndex++) {
            for (SortOperator.Ordering ordering : SortOperator.Ordering.values()) {
                SortOperator sortOp = new SortOperator(mockOp, columnIndex, ordering);

                int i = columnIndex;
                List<Object[]> sortedRows = Utils.tripsRows.stream()
                    .sorted((left, right) ->
                        ordering.compareNonNull(left[i], right[i], Utils.tripsColumns.get(i).type())
                    )
                    .toList();

                AssertOperator assertOp = new AssertOperator(sortOp, sortedRows);

                List<ColumnSpec> columnsSchema = assertOp.openExhaustCloseAndGetSchema();
                assertEquals(Utils.tripsColumns, columnsSchema);
            }
        }
    }

    @Test
    void countOperator() {
        MockOperator mockOp = new MockOperator(Utils.tripsColumns, Utils.tripsRows);

        for (int count : List.of(0, 5, Utils.tripsRows.size())) {
            LimitOperator limitOp = new LimitOperator(mockOp, count);

            CountOperator countOp = new CountOperator(limitOp);

            List<Object[]> countRows = List.<Object[]>of(new Object[] {(long) count});
            AssertOperator assertOp = new AssertOperator(countOp, countRows);

            List<ColumnSpec> countSchema = List.of(new ColumnSpec(CountOperator.COLUMN_NAME, ColumnType.LONG));
            List<ColumnSpec> columnsSchema = assertOp.openExhaustCloseAndGetSchema();
            assertEquals(countSchema, columnsSchema);
        }
    }
}
