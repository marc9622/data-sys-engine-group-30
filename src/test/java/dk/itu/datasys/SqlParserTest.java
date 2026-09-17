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
        SqlParserFacade parser = new SqlParserFacade();

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
        SqlParserFacade parser = new SqlParserFacade();

        List<Statement> statements = parser.parse(
                "SELECT * FROM trips WHERE city = '1';" +
                "SELECT * FROM trips WHERE city = 1;" +
                "SELECT * FROM trips WHERE city = 1.1;" +
                "SELECT * FROM trips WHERE distance > '100';" +
                "SELECT * FROM trips WHERE distance > 100;" +
                "SELECT * FROM trips WHERE distance > 100.5;" +
                "SELECT * FROM trips WHERE price < '50';" +
                "SELECT * FROM trips WHERE price < 50;" +
                "SELECT * FROM trips WHERE price < 50.75;");


        int index = 0;

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
        SqlParserFacade parser = new SqlParserFacade();

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
        SqlParserFacade parser = new SqlParserFacade();

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
        SqlParserFacade parser = new SqlParserFacade();

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
        SqlParserFacade parser = new SqlParserFacade();

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
        SqlParserFacade parser = new SqlParserFacade();

        List<Statement> statements = parser.parse(
                "SELECT * FROM trips WHERE distance < -100;" +
                "SELECT * FROM trips WHERE distance < -100.5;");

        int index = 0;

        assertEqualsSelectWhere("trips", "distance", Comparison.LESS_THAN, -100L, statements.get(index++));
        assertEqualsSelectWhere("trips", "distance", Comparison.LESS_THAN, -100.5, statements.get(index++));

        assertEquals(index, statements.size());
    }

    @Test
    void parsesNumberLimits() {
        SqlParserFacade parser = new SqlParserFacade();

        Statement s0 = parser.parse("SELECT * FROM trips WHERE distance < 9223372036854775807;").getFirst();
        Statement s1 = parser.parse("SELECT * FROM trips WHERE price < 179769313486231570814527423731704356798070567525844996598917476803157260780028538760589558632766878171540458953514382464234321326889464182768467546703537516986049910576551282076245490090389328944075868508455133942304583236903222948165808559332123348274797826204144723168738177180919299881250404026184124858368.0;").getFirst();
        Statement s2 = parser.parse("SELECT * FROM trips WHERE price < 0.00000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000002225073858507201383090232717332404064219215980462331830553327416887204434813918195854283159012511020564067339731035811005152434161553460108856012385377718821130777993532002330479610147442583636071921565046942503734208375250806650616658158948720491179968591639648500635908770118304874799780887753749949451580451605050915399856582470818645113537935804992115981085766051992433352114352390148795699609591288891602992641511063466313393663477586513029371762047325631781485664350872122828637642044846811407613911477062801689853244110024161447421618567166150540154285084716752901903161322778896729707373123334086988983175067838846926092773977972858659654941091369095406136467568702398678315290680984617210924625396728515625;").getFirst();
        Statement s3 = parser.parse("SELECT * FROM trips WHERE price < 0.000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000004940656458412465441765687928682213723650598026143247644255856825006755072702087518652998363616359923797965646954457177309266567103559397963987747960107818781263007131903114045278458171678489821036887186360569987307230500063874091535649843873124733972731696151400317153853980741262385655911710266585566867681870395603106249319452715914924553293054565444011274801297099995419319894090804165633245247571478690147267801593552386115501348035264934720193790268107107491703332226844753335720832431936092382893458368060106011506169809753078342277318329247904982524730776375927247874656084778203734469699533647017972677717585125660551199131504891101451037862738167250955837389733598993664809941164205702637090279242767544565229087538682506419718265533447265625;").getFirst();
        Statement s4 = parser.parse("SELECT * FROM trips WHERE price < 0.0;").getFirst();
        Statement s5 = parser.parse("SELECT * FROM trips WHERE distance > -9223372036854775808;").getFirst();
        Statement s6 = parser.parse("SELECT * FROM trips WHERE price > -179769313486231570814527423731704356798070567525844996598917476803157260780028538760589558632766878171540458953514382464234321326889464182768467546703537516986049910576551282076245490090389328944075868508455133942304583236903222948165808559332123348274797826204144723168738177180919299881250404026184124858368.0;").getFirst();
        Statement s7 = parser.parse("SELECT * FROM trips WHERE price > -0.00000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000002225073858507201383090232717332404064219215980462331830553327416887204434813918195854283159012511020564067339731035811005152434161553460108856012385377718821130777993532002330479610147442583636071921565046942503734208375250806650616658158948720491179968591639648500635908770118304874799780887753749949451580451605050915399856582470818645113537935804992115981085766051992433352114352390148795699609591288891602992641511063466313393663477586513029371762047325631781485664350872122828637642044846811407613911477062801689853244110024161447421618567166150540154285084716752901903161322778896729707373123334086988983175067838846926092773977972858659654941091369095406136467568702398678315290680984617210924625396728515625;").getFirst();
        Statement s8 = parser.parse("SELECT * FROM trips WHERE price > -0.000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000004940656458412465441765687928682213723650598026143247644255856825006755072702087518652998363616359923797965646954457177309266567103559397963987747960107818781263007131903114045278458171678489821036887186360569987307230500063874091535649843873124733972731696151400317153853980741262385655911710266585566867681870395603106249319452715914924553293054565444011274801297099995419319894090804165633245247571478690147267801593552386115501348035264934720193790268107107491703332226844753335720832431936092382893458368060106011506169809753078342277318329247904982524730776375927247874656084778203734469699533647017972677717585125660551199131504891101451037862738167250955837389733598993664809941164205702637090279242767544565229087538682506419718265533447265625;").getFirst();
        Statement s9 = parser.parse("SELECT * FROM trips WHERE price > -0.0;").getFirst();

        assertEqualsSelectWhere("trips", "distance", Comparison.LESS_THAN,     Long.MAX_VALUE,    s0);
        assertEqualsSelectWhere("trips", "price",    Comparison.LESS_THAN,     Double.MAX_VALUE,  s1);
        assertEqualsSelectWhere("trips", "price",    Comparison.LESS_THAN,     Double.MIN_NORMAL, s2);
        assertEqualsSelectWhere("trips", "price",    Comparison.LESS_THAN,     Double.MIN_VALUE,  s3);
        assertEqualsSelectWhere("trips", "price",    Comparison.LESS_THAN,     0.0,               s4);
        assertEqualsSelectWhere("trips", "distance", Comparison.GREATER_THAN,  Long.MIN_VALUE,    s5);
        assertEqualsSelectWhere("trips", "price",    Comparison.GREATER_THAN, -Double.MAX_VALUE,  s6);
        assertEqualsSelectWhere("trips", "price",    Comparison.GREATER_THAN, -Double.MIN_NORMAL, s7);
        assertEqualsSelectWhere("trips", "price",    Comparison.GREATER_THAN, -Double.MIN_VALUE,  s8);
        assertEqualsSelectWhere("trips", "price",    Comparison.GREATER_THAN, -0.0,               s9);
    }

    @Test
    void throwsSqlParseExceptionOnUnrepresentableNumbers() {
        SqlParserFacade parser = new SqlParserFacade();

        // Integer too positive
        assertThrows(SqlParseException.class, () ->
                parser.parse("SELECT * FROM trips WHERE distance > 9223372036854775808;"));

        // Integer too negative
        assertThrows(SqlParseException.class, () ->
                parser.parse("SELECT * FROM trips WHERE distance < -9223372036854775809;"));

        // Double too positive
        assertThrows(SqlParseException.class, () ->
                parser.parse("SELECT * FROM trips WHERE price > 179769313486231570814527423731704356798070567525844996598917476803157260780028538760589558632766878171540458953514382464234321326889464182768467546703537516986049910576551282076245490090389328944075868508455133942304583236903222948165808559332123348274797826204144723168738177180919299881250404026184124858369.0;"));

        // Double too negative
        assertThrows(SqlParseException.class, () ->
                parser.parse("SELECT * FROM trips WHERE price > -179769313486231570814527423731704356798070567525844996598917476803157260780028538760589558632766878171540458953514382464234321326889464182768467546703537516986049910576551282076245490090389328944075868508455133942304583236903222948165808559332123348274797826204144723168738177180919299881250404026184124858369.0;"));
    }
}
