package dk.itu.datasys;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

import dk.itu.datasys.Spec.*;

class SqlPrinterTest {

    @Test
    void prettyPrintsEveryStatementShape() {
        Statement create = Statement.createTable("trips").addColumns(Utils.tripsColumns).statement();

        Statement copy = Statement.copy("trips", "trips.csv");

        Statement selectWithWhere = Statement.select("trips").where("distance", Comparison.GREATER_THAN, 100L).limit(5).statement();

        Statement selectWithoutWhere = Statement.select("trips").columns("city", "price").statement();

        SqlPrinter printer = new SqlPrinter();

        assertEquals("CREATE TABLE trips (city STRING, distance LONG, price DOUBLE);",
                printer.print(create));
        assertEquals("COPY trips FROM 'trips.csv';",
                printer.print(copy));
        assertEquals("SELECT * FROM trips WHERE distance > 100 LIMIT 5;",
                printer.print(selectWithWhere));
        assertEquals("SELECT city, price FROM trips;",
                printer.print(selectWithoutWhere));
    }

    @Test
    void parsePrintRoundTripHoldsForEveryStatementShape() {
        SqlParser parser = new SqlParser();
        SqlPrinter printer = new SqlPrinter();

        List<Statement> statements = List.of(
                Statement.createTable("trips").addColumns(Utils.tripsColumns).statement(),
                Statement.copy("trips", "trips.csv"),
                Statement.select("trips").where("distance", Comparison.GREATER_THAN, 100L).statement(),
                Statement.select("trips").columns("city", "price").statement());

        for (Statement statement : statements) {
            assertEquals(statement, parser.parse(printer.print(statement)).getFirst());
        }
    }
}
