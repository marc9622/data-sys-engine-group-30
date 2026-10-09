package dk.itu.datasys;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import dk.itu.datasys.ops.Operator;

public final class Executor {
    private static final Logger LOGGER = LoggerFactory.getLogger(Executor.class);

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
    public List<List<Object[]>> executeScript(String sqlText) {
        MDC.put("sessionId", UUID.randomUUID().toString());
        MDC.put("statementNumber", "0");

        try {
            List<List<Object[]>> results = new ArrayList<>(); 

            List<Statement> statements = parser.parse(sqlText);
            for (int statementNumber = 0; statementNumber < statements.size(); statementNumber++){
                Statement statement = statements.get(statementNumber);
                MDC.put("statementNumber", String.valueOf(statementNumber + 1));
                LOGGER.debug("executing statement={}", statement);

                results.add(executeStatement(statement));
            } 
            return results;
        } finally {
            MDC.put("statementNumber", "0");
            LOGGER.debug("engine stopping");
            MDC.clear();
        }
    }

    /** Executes one already parsed statement. */
    public List<Object[]> executeStatement(Statement statement) {
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
