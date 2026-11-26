package dk.itu.datasys;

import static dk.itu.datasys.TestingUtils.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.Test;

import dk.itu.datasys.Spec.*;

class SqlPrinterTest {

    @Test
    void prettyPrintsEveryStatementShape() {
        Statement create = Statement.createTable("trips").addColumns(tripsColumns).statement();

        Statement copy = Statement.copy("trips", "trips.csv");

        Statement selectWithWhere = Statement.select("trips").where("distance", Comparison.GREATER_THAN, 100L).limit(5).statement();

        Statement selectWithoutWhere = Statement.select("trips").columns("city", "price").statement();

        Statement selectWithJoins = Statement.select(List.of("orders", "customers", "products"))
                .joinOn("o_custname", "c_name")
                .where("o_orderkey", Comparison.GREATER_THAN, 100L)
                .joinOn("o_prodname", "p_name")
                .statement();

        Statement deleteWithWhere = Statement.delete("trips")
                .where("distance", Comparison.GREATER_THAN, 100L)
                .statement();
        Statement deleteWithoutWhere = Statement.delete("trips").statement();

        assertEquals("CREATE TABLE trips (city STRING, distance LONG, price DOUBLE);",
                SqlPrinter.print(create));
        assertEquals("COPY trips FROM 'trips.csv';",
                SqlPrinter.print(copy));
        assertEquals("SELECT * FROM trips WHERE distance > 100 LIMIT 5;",
                SqlPrinter.print(selectWithWhere));
        assertEquals("SELECT city, price FROM trips;",
                SqlPrinter.print(selectWithoutWhere));
        assertEquals("SELECT * FROM orders, customers, products WHERE o_orderkey > 100 AND o_custname = c_name AND o_prodname = p_name;",
                SqlPrinter.print(selectWithJoins));
        assertEquals("DELETE FROM trips WHERE distance > 100;",
                SqlPrinter.print(deleteWithWhere));
        assertEquals("DELETE FROM trips;",
                SqlPrinter.print(deleteWithoutWhere));
    }

    @Test
    void parsePrintRoundTripHoldsForEveryStatementShape() {
        List<Statement> statements = List.of(
                Statement.createTable("trips").addColumns(tripsColumns).statement(),
                Statement.copy("trips", "trips.csv"),
                Statement.select("trips").where("distance", Comparison.GREATER_THAN, 100L).statement(),
                Statement.select("trips").columns("city", "price").statement(),
                Statement.select(List.of("orders", "customers", "products"))
                        .joinOn("o_custname", "c_name")
                        .where("o_orderkey", Comparison.GREATER_THAN, 100L)
                        .joinOn("o_prodname", "p_name")
                        .statement(),
                Statement.delete("trips").statement(),
                Statement.delete("trips").where("distance", Comparison.GREATER_THAN, 100L).statement());

        for (Statement statement : statements) {
            assertEquals(statement, SqlParser.parse(SqlPrinter.print(statement)).getFirst());
        }
    }
}
