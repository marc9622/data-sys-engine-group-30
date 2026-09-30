package dk.itu.datasys;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import dk.itu.datasys.ops.Operator;

public final class Executor {
    private final StorageEngine engine;
    private final SqlParser parser;
    private final Binder binder;
    private final Planner planner;

    public Executor(StorageEngine engine) {
        this.engine = Objects.requireNonNull(engine, "engine");
        this.parser = new SqlParser();
        this.binder = new Binder(engine);
        this.planner = new Planner(engine);
    }

    /** Executes every statement in a SQL script in input order. */
    public List<List<Object[]>> execute(String sqlText) {
        List<List<Object[]>> results = new ArrayList<>(); 
        for (Statement statement : parser.parse(sqlText))
            results.add(execute(statement)); 
        return results;
    }

    /** Executes one already parsed statement. */
    public List<Object[]> execute(Statement statement) {
        Objects.requireNonNull(statement, "statement");
        binder.bind(statement);

        return switch (statement) {
            case Statement.CreateTable create -> {
                engine.createTable(create.tableName(), create.columns());
                yield List.of();
            }
            case Statement.Copy copy -> {
                engine.copyFromCsvFile(copy.tableName(), copy.csvFilePath());
                yield List.of();
            }
            case Statement.Select select -> executeSelect(select);
        };
    }

    public List<Object[]> executeSelect(Statement.Select select) {
        Plan plan = planner.plan(select);
        engine.setLastScanStats(plan.scanStats());

        Operator root = plan.root();
        List<Object[]> rows = new ArrayList<>();
        root.open();
        try {
            Object[] row;
            while ((row = root.next()) != null)
                rows.add(row);
        } finally {
            root.close();
        }
        return rows;
    }
}