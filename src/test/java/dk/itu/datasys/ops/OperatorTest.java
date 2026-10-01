package dk.itu.datasys.ops;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.List;

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
        //Choose partitions 1 and 2 for scan 
        List<StorageEngine.PartitionMeta> selectedPartitions =
        partitions.subList(1, 3);
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
            ProjectOperator projectOp = new ProjectOperator(mockOp, projection);

            int i = columnIndex;
            List<Object[]> projectedRows = Utils.tripsRows.stream().map(row -> new Object[] {row[i]}).toList();
            AssertOperator assertOp = new AssertOperator(projectOp, projectedRows);

            List<ColumnSpec> columns = assertOp.openExhaustCloseAndGetSchema();
            assertEquals(projection, columns);
        }
    }
}
