package dk.itu.datasys;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import dk.itu.datasys.Spec.ColumnSpec;
import dk.itu.datasys.Spec.ColumnType;

public final class Binder {
    private final StorageEngine engine;

    public Binder(StorageEngine engine) {
        this.engine = Objects.requireNonNull(engine, "engine");
    }

    public void bind(Statement s) {
        Objects.requireNonNull(s, "s");

        switch (s) {
            case Statement.CreateTable create -> bindCreateTable(create);
            case Statement.Copy copy -> bindCopy(copy);
            case Statement.Select select -> bindSelect(select);
            default -> throw new IllegalArgumentException("unsupported statement: " + s);
        }
    }

    private void bindCreateTable(Statement.CreateTable create) {
        List<ColumnSpec> columns = create.columns();
        if (columns == null || columns.isEmpty()) {
            throw new IllegalArgumentException("empty column list");
        }

        HashSet<String> seen = new HashSet<>();
        for (ColumnSpec column : columns) {
            if (column == null || column.name() == null || column.name().isBlank()) {
                throw new IllegalArgumentException("invalid column name");
            }
            if (!seen.add(column.name())) {
                throw new IllegalArgumentException("duplicate column name: " + column.name());
            }
        }
    }

    private void bindCopy(Statement.Copy copy) {
        engine.schema(copy.tableName());
    }

    private void bindSelect(Statement.Select select) {
        List<ColumnSpec> schema = engine.schema(select.tableName()); 

        Optional<Statement.Select.Predicate> where = select.where(); 
        if (where.isEmpty()) {
            return; 
        }

        Statement.Select.Predicate predicate = where.get(); 
        ColumnSpec target = schema.stream()
                .filter(c -> c.name().equals(predicate.columnName()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown column: " + predicate.columnName()));

        if (!typesMatch(target.type(), predicate.constant())) {
            throw new IllegalArgumentException("constant type does not match column type");
        }
    }

    private static boolean typesMatch(ColumnType columnType, Object constant) {
        return switch (columnType) {
            case STRING -> constant instanceof String;
            case LONG -> constant instanceof Long;
            case DOUBLE -> constant instanceof Double;
        };
    }
}
