package dk.itu.datasys;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import dk.itu.datasys.Spec.ColumnSpec;
import dk.itu.datasys.Spec.ColumnType;
import dk.itu.datasys.Spec.Comparison;

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
}
