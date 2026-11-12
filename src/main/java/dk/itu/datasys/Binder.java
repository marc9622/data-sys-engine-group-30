package dk.itu.datasys;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dk.itu.datasys.Spec.ColumnSpec;
import dk.itu.datasys.StorageEngine.Catalog;

public final class Binder {
    private static final Logger LOGGER = LoggerFactory.getLogger(Binder.class);

    private final StorageEngine engine;

    public Binder(StorageEngine engine) {
        this.engine = Objects.requireNonNull(engine, "engine");
    }

    public BoundStatement bindStatement(Catalog catalog, Statement s) {
        Objects.requireNonNull(s, "s");

        return switch (s) {
            case Statement.CreateTable create -> bindCreateTable(catalog, create);
            case Statement.Copy copy -> bindCopy(catalog, copy);
            case Statement.Select select -> bindSelect(catalog, select);
            case Statement.Delete delete -> bindDelete(catalog, delete);
        };
    }

    private BoundStatement bindCreateTable(Catalog catalog, Statement.CreateTable create) {
        String table = create.tableName();

        if (catalog.tableByName(table).isPresent())
            throw new IllegalArgumentException("table already exists: " + table);

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

        return new BoundStatement.CreateTable(create.tableName(), create.columns());
    }

    private BoundStatement bindCopy(Catalog catalog, Statement.Copy copy) {
        String table = copy.tableName();

        Optional<Integer> tableIndex = catalog.tableIndexOf(table);
        if (tableIndex.isEmpty())
            throw new IllegalArgumentException("table does not exist: " + table);

        LOGGER.debug("bound copy statement table={} csvFilePath={}", table, copy.csvFilePath());

        Path csvPath = Path.of(copy.csvFilePath());

        return new BoundStatement.Copy(tableIndex.get(), csvPath);
    }

    private BoundStatement bindSelect(Catalog catalog, Statement.Select select) {
        String table = select.tableName();

        int tableIndex = catalog.tableIndexOf(table).orElseThrow(() ->
                new IllegalArgumentException("table does not exist: " + table));

        List<ColumnSpec> schema = engine.schema(catalog, tableIndex);
        List<String> schemaNames = schema.stream().map(ColumnSpec::name).toList();

        Optional<List<Integer>> columnIndices;
        if (select.columnNames().isEmpty()) {
            columnIndices = Optional.empty();
        }
        else {
            List<Integer> indices = new ArrayList<>();
            for (String column : select.columnNames().get()) {
                int columnIndex = schemaNames.indexOf(column);
                if (columnIndex == -1)
                    throw new IllegalArgumentException("unknown column: " + column);

                indices.add(columnIndex);
            }
            columnIndices = Optional.of(indices);
        }

        Optional<Statement.Predicate> where = select.where();
        bindWhere(schema, where);

        Optional<Integer> limit = select.limit();
        limit.ifPresent(count -> {
            if (count < 0)
                throw new IllegalArgumentException("limit must be non-negative");
        });

        String log = "bound select statement table=" + table;
        if (select.columnNames().isEmpty())
            log += " columns=*";
        else
            log += " columns=[" + String.join(" ", select.columnNames().get()) + "]";

        if (where.isPresent())
            log += " where=" + where.get();

        if (limit.isPresent())
            log += " limit=" + limit.get();

        LOGGER.debug(log);

        return new BoundStatement.Select(columnIndices, tableIndex, bindWhere(schema, where), limit);
    }

    private BoundStatement.Delete bindDelete(Catalog catalog, Statement.Delete delete) {
        String table = delete.tableName();

        int tableIndex = catalog.tableIndexOf(table).orElseThrow(() ->
                new IllegalArgumentException("table does not exist: " + table));

        List<ColumnSpec> schema = engine.schema(catalog, tableIndex);

        Optional<BoundStatement.Predicate> where = bindWhere(schema, delete.where());

        if (where.isPresent())
            LOGGER.debug("bound delete statement table={} where={}", table, where.get());
        else
            LOGGER.debug("bound delete statement table={} where=none", table);

        return new BoundStatement.Delete(tableIndex, where);
    }

    private static Optional<BoundStatement.Predicate> bindWhere(List<ColumnSpec> schema, Optional<Statement.Predicate> where) {
        if (where.isEmpty())
            return Optional.empty();

        Statement.Predicate predicate = where.get();

        int columnIndex = 0;
        for (; true; columnIndex++) {
            if (columnIndex == schema.size())
                throw new IllegalArgumentException("unknown column: " + predicate.columnName());

            if (schema.get(columnIndex).name().equals(predicate.columnName()))
                break;
        }

        if (predicate.constant().type() != schema.get(columnIndex).type())
            throw new IllegalArgumentException("constant type does not match column type");

        BoundStatement.Predicate bound = new BoundStatement.Predicate(columnIndex, predicate.comparison(), predicate.constant());

        return Optional.of(bound);
    }
}
