package dk.itu.datasys.ops;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.Test;

import dk.itu.datasys.Spec.*;
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
    void scanOperator() {
        // TODO: Requires refactor of ScanOperator and StorageEngine
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

