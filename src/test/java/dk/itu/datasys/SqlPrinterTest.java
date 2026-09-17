package dk.itu.datasys;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import dk.itu.datasys.Spec.*;

class SqlPrinterTest {

    @Test
    void prettyPrintsEveryStatementShape() {
        Statement create = new Statement.CreateTable("trips", List.of(
                new ColumnSpec("city", ColumnType.STRING),
                new ColumnSpec("distance", ColumnType.LONG),
                new ColumnSpec("price", ColumnType.DOUBLE)));

        Statement copy = new Statement.Copy("trips", "trips.csv");

        Statement selectWithWhere = new Statement.Select("trips",
                Optional.of(new Statement.Select.Predicate("distance", Comparison.GREATER_THAN, 100L)));

        Statement selectWithoutWhere = new Statement.Select("trips", Optional.empty());

        SqlPrinter printer = new SqlPrinter();

        assertEquals("CREATE TABLE trips (city STRING, distance LONG, price DOUBLE);",
                printer.print(create));
        assertEquals("COPY trips FROM 'trips.csv';",
                printer.print(copy));
        assertEquals("SELECT * FROM trips WHERE distance > 100;",
                printer.print(selectWithWhere));
        assertEquals("SELECT * FROM trips;",
                printer.print(selectWithoutWhere));
    }

    @Test
    void parsePrintRoundTripHoldsForEveryStatementShape() {
        SqlParserFacade parser = new SqlParserFacade();
        SqlPrinter printer = new SqlPrinter();

        List<Statement> statements = List.of(
                new Statement.CreateTable("trips", List.of(
                        new ColumnSpec("city", ColumnType.STRING),
                        new ColumnSpec("distance", ColumnType.LONG),
                        new ColumnSpec("price", ColumnType.DOUBLE))),
                new Statement.Copy("trips", "trips.csv"),
                new Statement.Select("trips",
                        Optional.of(new Statement.Select.Predicate("distance", Comparison.GREATER_THAN, 100L))),
                new Statement.Select("trips", Optional.empty()));

        for (Statement statement : statements) {
            assertEquals(statement, parser.parse(printer.print(statement)).getFirst());
        }
    }
}
