package dk.itu.datasys;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import dk.itu.datasys.Spec.*;

class SqlParserTest {

    @Test
    void parsesCreateCopyAndSelectStatements() {
        SqlParserFacade parser = new SqlParserFacade();

        List<Statement> statements = parser.parse(
                "CREATE TABLE trips (city STRING, distance LONG, price DOUBLE);" +
                "COPY trips FROM 'trips.csv';" +
                "SELECT * FROM trips WHERE distance > 100;");

        assertEquals(3, statements.size());
        assertEquals(new Statement.CreateTable("trips", List.of(
                new ColumnSpec("city", ColumnType.STRING),
                new ColumnSpec("distance", ColumnType.LONG),
                new ColumnSpec("price", ColumnType.DOUBLE))), statements.get(0));
        assertEquals(new Statement.Copy("trips", "trips.csv"), statements.get(1));
        assertEquals(new Statement.Select("trips",
                Optional.of(
                    new Statement.Select.Predicate("distance", Comparison.GREATER_THAN, 100L)
                )),
                statements.get(2));
    }

    @Test
    void throwsSqlParseExceptionWithLineAndColumn() {
        SqlParserFacade parser = new SqlParserFacade();

        SqlParseException ex = assertThrows(SqlParseException.class, () ->
                parser.parse("CREATE TABLE trips (city STRING, distance LONG, price DOUBLE"));

        assertEquals(1, ex.line());
        assertEquals(60, ex.column());

        SqlParseException ex1 = assertThrows(SqlParseException.class, () ->
                parser.parse("CREATE TABL trips (city STRING, distance LONG, price DOUBLE);"));

        assertEquals(1, ex1.line());
        assertEquals(7, ex1.column());

         SqlParseException ex2 = assertThrows(SqlParseException.class, () ->
                 parser.parse("CREATE TABLE trips (city STRING, distance FLOAT, price DOUBLE);"));

        assertEquals(1, ex2.line());
        assertEquals(42, ex2.column());
    }

    @Test
    void parsesCaseInsensitiveStatements() {
        SqlParserFacade parser = new SqlParserFacade();

        List<Statement> statements = parser.parse(
                "cReAtE tAbLe trips (city StRiNg, distance LoNg, price DoUbLe);" +
                "CoPy trips FrOm 'trips.csv';" +
                "SeLeCt * FrOm trips WhErE distance > 100;");

        assertEquals(3, statements.size());
        assertEquals(new Statement.CreateTable("trips", List.of(
                new ColumnSpec("city", ColumnType.STRING),
                new ColumnSpec("distance", ColumnType.LONG),
                new ColumnSpec("price", ColumnType.DOUBLE))), statements.get(0));
        assertEquals(new Statement.Copy("trips", "trips.csv"), statements.get(1));
        assertEquals(new Statement.Select("trips",
                Optional.of(
                    new Statement.Select.Predicate("distance", Comparison.GREATER_THAN, 100L)
                )),
                statements.get(2));
    }

    @Test
    void parsesStatementsWithComments() {
        SqlParserFacade parser = new SqlParserFacade();

        List<Statement> statements = parser.parse(
                "-- This is a comment\n" +
                "CREATE TABLE trips (\n" +
                "   city STRING, -- comment STRING\n" + 
                "   distance LONG,\n" +
                "   price DOUBLE\n" +
                "); -- Another comment\n" +
                "COPY trips FROM 'trips.csv';\n" +
                "SELECT * FROM trips WHERE distance > 100; -- Final comment\n");

        assertEquals(3, statements.size());
        assertEquals(new Statement.CreateTable("trips", List.of(
                new ColumnSpec("city", ColumnType.STRING),
                new ColumnSpec("distance", ColumnType.LONG),
                new ColumnSpec("price", ColumnType.DOUBLE))), statements.get(0));
        assertEquals(new Statement.Copy("trips", "trips.csv"), statements.get(1));
        assertEquals(new Statement.Select("trips",
                Optional.of(
                    new Statement.Select.Predicate("distance", Comparison.GREATER_THAN, 100L)
                )),
                statements.get(2));
    }

    @Test
    void parsesNegativeNumbers() {
        SqlParserFacade parser = new SqlParserFacade();

        List<Statement> statements = parser.parse(
                "CREATE TABLE trips (city STRING, distance LONG, price DOUBLE);" +
                "COPY trips FROM 'trips.csv';" +
                "SELECT * FROM trips WHERE distance < -100;");

        assertEquals(3, statements.size());
        assertEquals(new Statement.CreateTable("trips", List.of(
                new ColumnSpec("city", ColumnType.STRING),
                new ColumnSpec("distance", ColumnType.LONG),
                new ColumnSpec("price", ColumnType.DOUBLE))), statements.get(0));
        assertEquals(new Statement.Copy("trips", "trips.csv"), statements.get(1));
        assertEquals(new Statement.Select("trips",
                Optional.of(
                    new Statement.Select.Predicate("distance", Comparison.LESS_THAN, -100L)
                )),
                statements.get(2));
    }
}
