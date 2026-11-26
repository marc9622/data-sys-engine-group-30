package dk.itu.datasys;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dk.itu.datasys.Spec.ColumnSpec;
import dk.itu.datasys.Spec.ColumnType;
import dk.itu.datasys.StorageEngine.Catalog;

public final class Binder {
    private static final Logger LOGGER = LoggerFactory.getLogger(Binder.class);

    public static void bindStatement(Catalog catalog, Statement s) {
        Objects.requireNonNull(s, "s");

        switch (s) {
            case Statement.CreateTable create -> bindCreateTable(catalog, create);
            case Statement.Copy copy -> bindCopy(catalog, copy);
            case Statement.Select select -> bindSelect(catalog, select);
            case Statement.Delete delete -> bindDelete(catalog, delete);
        }
    }

    private static void bindCreateTable(Catalog catalog, Statement.CreateTable create) {
        String table = create.tableName();
        if (catalog.doesTableExist(table))
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
    }

    private static void bindCopy(Catalog catalog, Statement.Copy copy) {
        String table = copy.tableName();
        if (!catalog.doesTableExist(table))
            throw new IllegalArgumentException("table does not exist: " + table);

        LOGGER.debug("bound copy statement table={} csvFilePath={}", table, copy.csvFilePath());
    }

    private static void bindSelect(Catalog catalog, Statement.Select select) {
        ColumnResolver resolver = new ColumnResolver(catalog, select.tableNames());
        select.columns().forEach(resolver::resolve);
        select.filters().forEach(filter -> {
            ColumnResolver.Resolution target = resolver.resolve(filter.columnName());
            bindFilter(List.of(target.column()), filter);
        });
        select.joins().forEach(join -> bindJoinOn(resolver, join));

        Optional<Integer> limit = select.limit();
        limit.ifPresent(count -> {
            if (count < 0)
                throw new IllegalArgumentException("limit must be non-negative");
        });

        String log = "bound select statement table=[" + String.join(" ", select.tableNames()) + "]";
        if (select.columns().isEmpty())
            log += " columns=*";
        else
            log += " columns=[" + String.join(" ", select.columns()) + "]";

        if (!select.filters().isEmpty())
            log += " where=[" + String.join(" ", select.filters().stream().map(Object::toString).toList()) + "]";

        if (!select.joins().isEmpty())
            log += " joins=[" + String.join(" ", select.joins().stream().map(Object::toString).toList()) + "]";

        if (limit.isPresent())
            log += " limit=" + limit.get();

        LOGGER.debug(log);
        return; 
    }

    private static void bindDelete(Catalog catalog, Statement.Delete delete) {
        String table = delete.tableName();
        if (!catalog.doesTableExist(table))
            throw new IllegalArgumentException("table does not exist: " + table);

        List<ColumnSpec> schema = catalog.schema(table);

        Optional<Statement.FilterPredicate> filter = delete.filter();
        if (filter.isPresent()) {
            bindFilter(schema, filter.get());

            LOGGER.debug("bound delete statement table={} where={}", table, filter.get());
        }
        else {
            LOGGER.debug("bound delete statement table={} where=none", table);
        }
    }

    private static void bindFilter(List<ColumnSpec> schema, Statement.FilterPredicate filter) {
        ColumnSpec target = schema.stream()
            .filter(c -> c.name().equals(filter.columnName()))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("unknown column: " + filter.columnName()));

        if (!typesMatch(target.type(), filter.constant()))
            throw new IllegalArgumentException("constant type does not match column type");
    }

    private static void bindJoinOn(ColumnResolver resolver, Statement.JoinPredicate join) {
        ColumnResolver.Resolution leftTarget = resolver.resolve(join.leftColumnName());
        ColumnResolver.Resolution rightTarget = resolver.resolve(join.rightColumnName());

        if (leftTarget.tableName().equals(rightTarget.tableName()))
            throw new IllegalArgumentException("join columns must be from different tables");

        if (leftTarget.column().type() != rightTarget.column().type())
            throw new IllegalArgumentException("join columns must have the same type");
    }

    private static boolean typesMatch(ColumnType columnType, Object constant) {
        return switch (columnType) {
            case STRING -> constant instanceof String;
            case LONG -> constant instanceof Long;
            case DOUBLE -> constant instanceof Double;
        };
    }
}
