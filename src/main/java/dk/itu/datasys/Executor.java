package dk.itu.datasys;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import dk.itu.datasys.StorageEngine.Catalog;
import dk.itu.datasys.StorageEngine.ScanStats;
import dk.itu.datasys.StorageEngine.TableMeta;
import dk.itu.datasys.ops.Operator;

public final class Executor {
    private static final Logger LOGGER = LoggerFactory.getLogger(Executor.class);

    private final StorageEngine engine;
    private final SqlParser parser;
    private final Binder binder;
    private final Planner planner;

    private ScanStats lastScanStats = null;

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

            List<Statement> statements = Utils.logExceptions(LOGGER, "failed to parse query", () ->
                    parser.parse(sqlText));

            for (int statementNumber = 0; statementNumber < statements.size(); statementNumber++) {
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

        Catalog catalog = engine.openCatalog();

        try {
            Utils.logExceptions(LOGGER, "failed to bind statement", () ->
                binder.bindStatement(catalog, statement));

            List<Object[]> result = switch (statement) {
                case Statement.CreateTable create ->
                    Utils.logExceptions(LOGGER, "failed to create table", () -> {
                        engine.createTable(catalog, create.tableName(), create.columns());
                        return List.of();
                    });
                case Statement.Copy copy ->
                    Utils.logExceptions(LOGGER, "failed to copy csv file", () -> {
                        engine.copyFromCsvFile(catalog, copy.tableName(), Path.of(copy.csvFilePath()));
                        return List.of();
                    });
                case Statement.Select select ->
                    Utils.logExceptions(LOGGER, "failed to execute query", () ->
                        executeSelect(catalog, select));
                case Statement.Delete delete ->
                    Utils.logExceptions(LOGGER, "failed to execute delete", () -> {
                        executeDelete(catalog, delete);
                        return List.of();
                    });
            };
        
            return result;
        }
        finally {
            engine.closeCatalog(catalog);
        }
    }

    public List<Object[]> executeSelect(Catalog catalog, Statement.Select select) {
        ExecuteQueryResult result = executeQuery(catalog, select);

        double elapsedMs = result.planningMs + result.executionMs;

        LOGGER.debug("statement=SELECT table={} rowsOut={} partitionsRead={} partitionsPruned={} version={} durationMs={}",
                 select.tableName(), result.rows.size(), lastScanStats.partitionsRead(), lastScanStats.partitionsPruned(), result.tableVersion, elapsedMs);
        LOGGER.debug("select planningMs={} executionMs={} elapsedMs={}",
                result.planningMs, result.executionMs, elapsedMs);
                
        return result.rows;
    }

    public void executeDelete(Catalog catalog, Statement.Delete delete) {
        ExecuteQueryResult result = executeQuery(catalog, delete.getMatchingSelect());

        long deletionStart = System.nanoTime();
        int rowsDeleted = engine.deleteFromTable(catalog, delete.tableName(), delete.where());

        double deletionMs = (System.nanoTime() - deletionStart) / 1_000_000.0;

        double elapsedMs = result.planningMs + result.executionMs + deletionMs;

        LOGGER.debug("statement=DELETE table={} rowsDeleted={} partitionsRead={} partitionsPruned={} version={} durationMs={}",
                 delete.tableName(), rowsDeleted, lastScanStats.partitionsRead(), lastScanStats.partitionsPruned(),
                 result.tableVersion + 1, elapsedMs);
        LOGGER.debug("delete planningMs={} executionMs={} deletionMs={} elapsedMs={}",
                result.planningMs, result.executionMs, deletionMs, elapsedMs);
    }

    public static record ExecuteQueryResult(List<Object[]> rows, int tableVersion, double planningMs, double executionMs) {}

    public ExecuteQueryResult executeQuery(Catalog catalog, Statement.Select select) {
        long planningStart = System.nanoTime();

        Plan plan = Utils.logExceptions(LOGGER, "failed to make plan", () ->
            planner.plan(catalog, select));

        lastScanStats = plan.scanStats();

        double planningMs = (System.nanoTime() - planningStart) / 1_000_000.0;

        long executionStart = System.nanoTime();

        Operator root = plan.root();
        List<Object[]> rows = new ArrayList<>();

        Utils.logExceptions(LOGGER, "failed to open operations", () ->
            root.open());
        try {
            Utils.logExceptions(LOGGER, "failed to get rows from operations", () -> {
                root.exhaust(rows);
            });
        } finally {
            Utils.logExceptions(LOGGER, "failed to close operations", () ->
                root.close());
        }

        double executionMs = (System.nanoTime() - executionStart) / 1_000_000.0;
        
        TableMeta tableMeta = catalog.tables.get(select.tableName());

        return new ExecuteQueryResult(rows, tableMeta.getVersion(), planningMs, executionMs);
    }

    public ScanStats getLastScanStats() {
        return lastScanStats;
    }
}
