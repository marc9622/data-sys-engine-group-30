package dk.itu.datasys;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dk.itu.datasys.Spec.ColumnSpec;
import dk.itu.datasys.Spec.ColumnType;

public final class Binder {
    private static final Logger LOGGER = LoggerFactory.getLogger(Binder.class);

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
        if (columns == null || columns.isEmpty())
            throw new IllegalArgumentException("empty column list");

        HashSet<String> seen = new HashSet<>();
        for (ColumnSpec column : columns) {
            if (column == null || column.name() == null || column.name().isBlank())
                throw new IllegalArgumentException("invalid column name");

            if (!seen.add(column.name()))
                throw new IllegalArgumentException("duplicate column name: " + column.name());
        }

        LOGGER.debug("bound create table statement table={} columns={}", create.tableName(), columns.size());
    }

    private void bindCopy(Statement.Copy copy) {
        if (!engine.doesTableExist(copy.tableName()))
            throw new IllegalArgumentException("table does not exist: " + copy.tableName());

        LOGGER.debug("bound copy statement table={} csvFilePath={}", copy.tableName(), copy.csvFilePath());
    }

    private void bindSelect(Statement.Select select) {
        List<ColumnSpec> schema = engine.schema(select.tableName()); 

        Optional<Statement.Select.Predicate> where = select.where(); 
        if (where.isEmpty()) {
            LOGGER.debug("bound select statement table={}", select.tableName());
            return; 
        }

        Statement.Select.Predicate predicate = where.get(); 
        ColumnSpec target = schema.stream()
                .filter(c -> c.name().equals(predicate.columnName()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown column: " + predicate.columnName()));

        if (!typesMatch(target.type(), predicate.constant()))
            throw new IllegalArgumentException("constant type does not match column type");

        LOGGER.debug("bound select statement table={} where={}", select.tableName(), predicate);
    }

    private static boolean typesMatch(ColumnType columnType, Object constant) {
        return switch (columnType) {
            case STRING -> constant instanceof String;
            case LONG -> constant instanceof Long;
            case DOUBLE -> constant instanceof Double;
        };
    }
}
