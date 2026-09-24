package dk.itu.datasys.ops;

import java.util.List;

import dk.itu.datasys.Spec.ColumnSpec;

public final class ProjectOperator extends Operator.Intermediate {
    private final List<ColumnSpec> columns;
    private int[] mapping = null;

    public ProjectOperator(Operator child, List<ColumnSpec> columns) {
        super(child);
        this.columns = columns;
    }

    @Override
    protected void openIntermediate() {
        List<String> sourceColumnNames = childSchema().stream().map(ColumnSpec::name).toList();

        mapping = columns.stream().mapToInt(column -> {
            for (int sourceColumnIndex = 0; sourceColumnIndex < sourceColumnNames.size(); sourceColumnIndex++) {
                if (sourceColumnNames.get(sourceColumnIndex).equals(column.name())) {
                    return sourceColumnIndex;
                }
            }
            throw new IllegalArgumentException("Column " + column.name() + " not found in source schema");
        }).toArray();
    }

    @Override
    public List<ColumnSpec> schema() {
        return columns;
    }

    @Override
    protected Object[] nextIntermediate() {
        Object[] row = next();
        if (row == null) {
            return null;
        }

        Object[] result = new Object[mapping.length];
        for (int i = 0; i < mapping.length; i++) {
            result[i] = row[mapping[i]];
        }
        return result;
    }

    @Override
    protected void closeIntermediate() {
        // No-op
    }
}
