package dk.itu.datasys;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import dk.itu.datasys.Spec.*;

class SqlParserTest {

    private static void assertEqualsSelectWhere(String table, String column, Comparison comparison, Object value, Statement actual) {
        assertEquals(new Statement.Select(table,
                Optional.of(
                    new Statement.Select.Predicate(column, comparison, value)
                )),
                actual);
    }

    @Test
    void parsesCreateCopyAndSelectStatements() {
        SqlParser parser = new SqlParser();

        List<Statement> statements = parser.parse(
                "CREATE TABLE trips (city STRING, distance LONG, price DOUBLE);" +
                "COPY trips FROM 'trips.csv';" +
                "SELECT * FROM trips;" +
                "SELECT * FROM trips WHERE distance > 100;");

        int index = 0;

        assertEquals(new Statement.CreateTable("trips", List.of(
                new ColumnSpec("city", ColumnType.STRING),
                new ColumnSpec("distance", ColumnType.LONG),
                new ColumnSpec("price", ColumnType.DOUBLE))), statements.get(index++));
        assertEquals(new Statement.Copy("trips", "trips.csv"), statements.get(index++));
        assertEquals(new Statement.Select("trips", Optional.empty()), statements.get(index++));
        assertEqualsSelectWhere("trips", "distance", Comparison.GREATER_THAN, 100L, statements.get(index++));

        assertEquals(index, statements.size());
    }

    @Test
    void parsesNumbersCorrect() {
        SqlParser parser = new SqlParser();

        List<Statement> statements = parser.parse(
                "CREATE TABLE trips (city STRING, distance LONG, price DOUBLE);" +
                "COPY trips FROM 'trips.csv';" +
                "SELECT * FROM trips WHERE city = '1';" +
                "SELECT * FROM trips WHERE city = 1;" +
                "SELECT * FROM trips WHERE city = 1.1;" +
                "SELECT * FROM trips WHERE distance > '100';" +
                "SELECT * FROM trips WHERE distance > 100;" +
                "SELECT * FROM trips WHERE distance > 100.5;" +
                "SELECT * FROM trips WHERE price < '50';" +
                "SELECT * FROM trips WHERE price < 50;" +
                "SELECT * FROM trips WHERE price < 50.75;");


        int index = 2;

        assertEqualsSelectWhere("trips", "city", Comparison.EQUALS, "1", statements.get(index++));
        assertEqualsSelectWhere("trips", "city", Comparison.EQUALS, 1L, statements.get(index++));
        assertEqualsSelectWhere("trips", "city", Comparison.EQUALS, 1.1, statements.get(index++));
        assertEqualsSelectWhere("trips", "distance", Comparison.GREATER_THAN, "100", statements.get(index++));
        assertEqualsSelectWhere("trips", "distance", Comparison.GREATER_THAN, 100L, statements.get(index++));
        assertEqualsSelectWhere("trips", "distance", Comparison.GREATER_THAN, 100.5, statements.get(index++));
        assertEqualsSelectWhere("trips", "price", Comparison.LESS_THAN, "50", statements.get(index++));
        assertEqualsSelectWhere("trips", "price", Comparison.LESS_THAN, 50L, statements.get(index++));
        assertEqualsSelectWhere("trips", "price", Comparison.LESS_THAN, 50.75, statements.get(index++));

        assertEquals(index, statements.size());
    }

    @Test
    void throwsSqlParseExceptionWithLineAndColumn() {
        SqlParser parser = new SqlParser();

        // Missing right-paren
        SqlParseException ex = assertThrows(SqlParseException.class, () ->
                parser.parse("CREATE TABLE trips \n (city STRING, distance LONG, price DOUBLE;"));

        assertEquals(2, ex.line());
        assertEquals(42, ex.column());

        // Misspelled keyword
        SqlParseException ex1 = assertThrows(SqlParseException.class, () ->
                parser.parse("CREATE TABL trips \n (city STRING, distance LONG, price DOUBLE);"));

        assertEquals(1, ex1.line());
        assertEquals(7, ex1.column());

        // Unknown type
        SqlParseException ex2 = assertThrows(SqlParseException.class, () ->
                 parser.parse("CREATE TABLE trips \n (city STRING, distance FLOAT, price DOUBLE);"));

        assertEquals(2, ex2.line());
        assertEquals(24, ex2.column());

        // Missing semicolon
        SqlParseException ex3 = assertThrows(SqlParseException.class, () ->
                parser.parse("CREATE TABLE trips \n (city STRING, distance LONG, price DOUBLE)"));

        assertEquals(2, ex3.line());
        assertEquals(43, ex3.column());

        // Unterminated string
        SqlParseException ex4 = assertThrows(SqlParseException.class, () ->
                parser.parse("COPY trips FROM 'trips.csv; \n (city STRING, distance LONG, price DOUBLE);"));

        assertEquals(1, ex4.line());
        assertEquals(16, ex4.column());

        // Missing keyword
        SqlParseException ex5 = assertThrows(SqlParseException.class, () ->
                parser.parse("SELECT * trips WHERE distance > 100;"));

        assertEquals(1, ex5.line());
        assertEquals(9, ex5.column());

        // Missing left-paren
        SqlParseException ex6 = assertThrows(SqlParseException.class, () ->
                parser.parse("CREATE TABLE trips \n ) city STRING, distance LONG, price DOUBLE);"));

        assertEquals(2, ex6.line());
        assertEquals(1, ex6.column());
    }

    @Test
    void parsesCaseInsensitiveStatements() {
        SqlParser parser = new SqlParser();

        List<Statement> statements = parser.parse(
                "cReAtE tAbLe trips (city StRiNg, distance LoNg, price DoUbLe);" +
                "CoPy trips FrOm 'trips.csv';" +
                "SeLeCt * FrOm trips WhErE distance > 100;");

        int index = 0;

        assertEquals(new Statement.CreateTable("trips", List.of(
                new ColumnSpec("city", ColumnType.STRING),
                new ColumnSpec("distance", ColumnType.LONG),
                new ColumnSpec("price", ColumnType.DOUBLE))), statements.get(index++));
        assertEquals(new Statement.Copy("trips", "trips.csv"), statements.get(index++));
        assertEqualsSelectWhere("trips", "distance", Comparison.GREATER_THAN, 100L, statements.get(index++));

        assertEquals(index, statements.size());
    }

    @Test
    void parsesCaseSensitiveIdentifiers() {
        SqlParser parser = new SqlParser();

        List<Statement> statements = parser.parse(
                "CREATE TABLE TrIpS (city STRING, Distance LONG, PRICE DOUBLE);" +
                "COPY TrIpS FROM 'trips.csv';" +
                "SELECT * FROM TrIpS WHERE city = 'Copenhagen';" +
                "SELECT * FROM TrIpS WHERE Distance > 100;" +
                "SELECT * FROM TrIpS WHERE PRICE < 50.75;");

        int index = 0;

        assertEquals(new Statement.CreateTable("TrIpS", List.of(
                new ColumnSpec("city", ColumnType.STRING),
                new ColumnSpec("Distance", ColumnType.LONG),
                new ColumnSpec("PRICE", ColumnType.DOUBLE))), statements.get(index++));
        assertEquals(new Statement.Copy("TrIpS", "trips.csv"), statements.get(index++));
        assertEqualsSelectWhere("TrIpS", "city", Comparison.EQUALS, "Copenhagen", statements.get(index++));
        assertEqualsSelectWhere("TrIpS", "Distance", Comparison.GREATER_THAN, 100L, statements.get(index++));
        assertEqualsSelectWhere("TrIpS", "PRICE", Comparison.LESS_THAN, 50.75, statements.get(index++));

        assertEquals(index, statements.size());

        assertNotEquals(new Statement.CreateTable("trips", List.of(
                new ColumnSpec("city", ColumnType.STRING),
                new ColumnSpec("Distance", ColumnType.LONG),
                new ColumnSpec("PRICE", ColumnType.DOUBLE))), statements.get(0));

        assertNotEquals(new Statement.CreateTable("TrIpS", List.of(
                new ColumnSpec("CITY", ColumnType.STRING),
                new ColumnSpec("Distance", ColumnType.LONG),
                new ColumnSpec("PRICE", ColumnType.DOUBLE))), statements.get(0));

        assertNotEquals(new Statement.CreateTable("TrIpS", List.of(
                new ColumnSpec("city", ColumnType.STRING),
                new ColumnSpec("distance", ColumnType.LONG),
                new ColumnSpec("PRICE", ColumnType.DOUBLE))), statements.get(0));

        assertNotEquals(new Statement.CreateTable("TrIpS", List.of(
                new ColumnSpec("city", ColumnType.STRING),
                new ColumnSpec("Distance", ColumnType.LONG),
                new ColumnSpec("price", ColumnType.DOUBLE))), statements.get(0));
    }

    @Test
    void parsesStatementsWithComments() {
        SqlParser parser = new SqlParser();

        List<Statement> statements = parser.parse(
                "-- This is a comment\n" +
                "CREATE TABLE trips (\n" +
                "   city STRING, -- commentedColumn STRING,\n" + 
                "   distance LONG,\n" +
                "   price DOUBLE\n" +
                "); -- Another comment\n" +
                "COPY trips FROM 'trips.csv';\n" +
                "SELECT * FROM trips WHERE distance > 100; -- Final comment\n");

        int index = 0;

        assertEquals(new Statement.CreateTable("trips", List.of(
                new ColumnSpec("city", ColumnType.STRING),
                new ColumnSpec("distance", ColumnType.LONG),
                new ColumnSpec("price", ColumnType.DOUBLE))), statements.get(index++));
        assertEquals(new Statement.Copy("trips", "trips.csv"), statements.get(index++));
        assertEqualsSelectWhere("trips", "distance", Comparison.GREATER_THAN, 100L, statements.get(index++));

        assertEquals(index, statements.size());
    }

    @Test
    void parsesNegativeNumbers() {
        SqlParser parser = new SqlParser();

        List<Statement> statements = parser.parse(
                "CREATE TABLE trips (city STRING, distance LONG, price DOUBLE);" +
                "COPY trips FROM 'trips.csv';" +
                "SELECT * FROM trips WHERE distance < -100;" +
                "SELECT * FROM trips WHERE distance < -100.5;");

        int index = 0;

        assertEquals(new Statement.CreateTable("trips", List.of(
                new ColumnSpec("city", ColumnType.STRING),
                new ColumnSpec("distance", ColumnType.LONG),
                new ColumnSpec("price", ColumnType.DOUBLE))), statements.get(index++));
        assertEquals(new Statement.Copy("trips", "trips.csv"), statements.get(index++));
        assertEqualsSelectWhere("trips", "distance", Comparison.LESS_THAN, -100L, statements.get(index++));
        assertEqualsSelectWhere("trips", "distance", Comparison.LESS_THAN, -100.5, statements.get(index++));

        assertEquals(index, statements.size());
    }
}
