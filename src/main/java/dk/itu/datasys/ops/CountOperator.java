package dk.itu.datasys.ops;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dk.itu.datasys.Spec.*;
import dk.itu.datasys.StorageEngine.Row;

public final class CountOperator extends Operator.ExhaustIntermediate {
    private static final Logger LOGGER = LoggerFactory.getLogger(CountOperator.class);

    public static final String COLUMN_NAME = "count";

    private static final List<ColumnSpec> schema = List.of(new ColumnSpec(COLUMN_NAME, ColumnType.LONG));

    protected CountOperator(Operator child) {
        super(child);
    }

    @Override
    protected List<Row> openAndExhaustIntermediate(List<ColumnSpec> childSchema, List<Row> childRows) {
        Object[] data = new Object[] {(long) childRows.size()};
        return List.of(Row.ofEphemeral(data));
    }

    @Override
    public List<ColumnSpec> schema() {
        return schema;
    }

    @Override
    protected void closeIntermediate() {
        LOGGER.debug("count={}", schema.get(0).name());
    }
}
