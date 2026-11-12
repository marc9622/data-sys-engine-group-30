package dk.itu.datasys.ops;

import java.util.List;

import dk.itu.datasys.Spec.ColumnSpec;
import dk.itu.datasys.Spec.Value;

public final class ProjectOperator extends Operator.RowWiseIntermediate {
    private final List<Integer> columns;
    private List<ColumnSpec> schema;

    public ProjectOperator(Operator child, List<Integer> columns) {
        super(child);
        this.columns = columns;
    }

    @Override
    protected void openIntermediate(List<ColumnSpec> srcColumns) {
        schema = columns.stream().map(srcColumns::get).toList();
    }

    @Override
    public List<ColumnSpec> schema() {
        return schema;
    }

    @Override
    protected NextResult nextIntermediate(Value[] row) {
        Value[] result = new Value[columns.size()];
        for (int i = 0; i < columns.size(); i++) {
            result[i] = row[columns.get(i)];
        }
        return NextResult.of(result);
    }

    @Override
    protected void closeIntermediate() {
        // No-op
    }
}
