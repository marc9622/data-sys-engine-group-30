package dk.itu.datasys.ops;

import java.util.List;
import java.util.stream.IntStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dk.itu.datasys.Spec.ColumnSpec;

public final class ProjectOperator extends Operator.RowWiseIntermediate {
    private static final Logger LOGGER = LoggerFactory.getLogger(ProjectOperator.class);

    private final List<String> columns;

    private int[] mapping;
    private List<ColumnSpec> schema;

    public ProjectOperator(Operator child, List<String> columns) {
        super(child);
        this.columns = columns;

        this.mapping = null;
        this.schema = null;
    }

    @Override
    protected void openIntermediate(List<ColumnSpec> srcColumns) {
        List<String> srcColumnNames = srcColumns.stream().map(ColumnSpec::name).toList();

        mapping = columns.stream().mapToInt(column -> {
            for (int sourceColumnIndex = 0; sourceColumnIndex < srcColumnNames.size(); sourceColumnIndex++) {
                if (srcColumnNames.get(sourceColumnIndex).equals(column))
                    return sourceColumnIndex;
            }
            LOGGER.error("invalid column name column={} sourceColumns=[{}]", column, String.join(" ", srcColumnNames));
            throw new IllegalArgumentException("Column " + column + " not found in source schema");
        }).toArray();

        schema = IntStream.of(mapping).mapToObj(srcColumns::get).toList();
    }

    @Override
    public List<ColumnSpec> schema() {
        return schema;
    }

    @Override
    protected NextResult nextIntermediate(Object[] row) {
        Object[] result = new Object[mapping.length];
        for (int i = 0; i < mapping.length; i++) {
            result[i] = row[mapping[i]];
        }
        return NextResult.of(result);
    }

    @Override
    protected void closeIntermediate() {
        // No-op
    }
}
