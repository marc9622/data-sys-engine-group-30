package dk.itu.datasys;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.Test;

import dk.itu.datasys.Spec.*;

class SqlParserTest {

    @Test
    void parsesCreateCopyAndSelectStatements() {
        SqlParser parser = new SqlParser();

        List<Statement> statements = parser.parse("""
                CREATE TABLE trips (city STRING, distance LONG, price DOUBLE);
                COPY trips FROM 'trips.csv';
                SELECT * FROM trips;
                SELECT * FROM trips WHERE distance > 100;""");

        int index = 0;

        assertEquals(Statement.createTable("trips").addColumns(Utils.tripsColumns).statement(), statements.get(index++));
        assertEquals(Statement.copy("trips", "trips.csv"), statements.get(index++));
        assertEquals(Statement.select("trips").statement(), statements.get(index++));
        assertEquals(Statement.select("trips").where("distance", Comparison.GREATER_THAN, 100L).statement(), statements.get(index++));

        assertEquals(index, statements.size());
    }

    @Test
    void parsesNumbersCorrect() {
        SqlParser parser = new SqlParser();

        List<Statement> statements = parser.parse("""
                SELECT * FROM trips WHERE city = '1';
                SELECT * FROM trips WHERE city = 1;
                SELECT * FROM trips WHERE city = 1.1;
                SELECT * FROM trips WHERE distance > '100';
                SELECT * FROM trips WHERE distance > 100;
                SELECT * FROM trips WHERE distance > 100.5;
                SELECT * FROM trips WHERE price < '50';
                SELECT * FROM trips WHERE price < 50;
                SELECT * FROM trips WHERE price < 50.75;""");


        int index = 0;

        assertEquals(Statement.select("trips").where("city", Comparison.EQUALS, "1").statement(), statements.get(index++));
        assertEquals(Statement.select("trips").where("city", Comparison.EQUALS, 1L).statement(), statements.get(index++));
        assertEquals(Statement.select("trips").where("city", Comparison.EQUALS, 1.1).statement(), statements.get(index++));
        assertEquals(Statement.select("trips").where("distance", Comparison.GREATER_THAN, "100").statement(), statements.get(index++));
        assertEquals(Statement.select("trips").where("distance", Comparison.GREATER_THAN, 100L).statement(), statements.get(index++));
        assertEquals(Statement.select("trips").where("distance", Comparison.GREATER_THAN, 100.5).statement(), statements.get(index++));
        assertEquals(Statement.select("trips").where("price", Comparison.LESS_THAN, "50").statement(), statements.get(index++));
        assertEquals(Statement.select("trips").where("price", Comparison.LESS_THAN, 50L).statement(), statements.get(index++));
        assertEquals(Statement.select("trips").where("price", Comparison.LESS_THAN, 50.75).statement(), statements.get(index++));

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

        List<Statement> statements = parser.parse("""
                cReAtE tAbLe trips (city StRiNg, distance LoNg, price DoUbLe);
                CoPy trips FrOm 'trips.csv';
                SeLeCt * FrOm trips WhErE distance > 100;""");

        int index = 0;

        assertEquals(Statement.createTable("trips").addColumns(Utils.tripsColumns).statement(), statements.get(index++));
        assertEquals(Statement.copy("trips", "trips.csv"), statements.get(index++));
        assertEquals(Statement.select("trips").where("distance", Comparison.GREATER_THAN, 100L).statement(), statements.get(index++));

        assertEquals(index, statements.size());
    }

    @Test
    void parsesCaseSensitiveIdentifiers() {
        SqlParser parser = new SqlParser();

        List<Statement> statements = parser.parse("""
                CREATE TABLE TrIpS (city STRING, Distance LONG, PRICE DOUBLE);
                COPY TrIpS FROM 'trips.csv';
                SELECT * FROM TrIpS WHERE city = 'Copenhagen';
                SELECT * FROM TrIpS WHERE Distance > 100;
                SELECT * FROM TrIpS WHERE PRICE < 50.75;""");

        int index = 0;

        assertEquals(Statement.createTable("TrIpS").addColumn("city", ColumnType.STRING).addColumn("Distance", ColumnType.LONG).addColumn("PRICE", ColumnType.DOUBLE).statement(), statements.get(index++));
        assertEquals(Statement.copy("TrIpS", "trips.csv"), statements.get(index++));
        assertEquals(Statement.select("TrIpS").where("city", Comparison.EQUALS, "Copenhagen").statement(), statements.get(index++));
        assertEquals(Statement.select("TrIpS").where("Distance", Comparison.GREATER_THAN, 100L).statement(), statements.get(index++));
        assertEquals(Statement.select("TrIpS").where("PRICE", Comparison.LESS_THAN, 50.75).statement(), statements.get(index++));

        assertEquals(index, statements.size());

        assertNotEquals(Statement.createTable("trips")
                .addColumn("city", ColumnType.STRING)
                .addColumn("Distance", ColumnType.LONG)
                .addColumn("PRICE", ColumnType.DOUBLE).statement(), statements.get(0));

        assertNotEquals(Statement.createTable("TrIpS")
                .addColumn("CITY", ColumnType.STRING)
                .addColumn("Distance", ColumnType.LONG)
                .addColumn("PRICE", ColumnType.DOUBLE).statement(), statements.get(0));

        assertNotEquals(Statement.createTable("TrIpS")
                .addColumn("city", ColumnType.STRING)
                .addColumn("distance", ColumnType.LONG)
                .addColumn("PRICE", ColumnType.DOUBLE).statement(), statements.get(0));

        assertNotEquals(Statement.createTable("TrIpS")
                .addColumn("city", ColumnType.STRING)
                .addColumn("Distance", ColumnType.LONG)
                .addColumn("price", ColumnType.DOUBLE).statement(), statements.get(0));
    }

    @Test
    void parsesStatementsWithComments() {
        SqlParser parser = new SqlParser();

        List<Statement> statements = parser.parse("""
                -- This is a comment
                CREATE TABLE trips (
                   city STRING, -- commentedColumn STRING,
                   distance LONG,
                   price DOUBLE
                ); -- Another comment
                COPY trips FROM 'trips.csv';
                SELECT * FROM trips WHERE distance > 100; -- Final comment""");

        int index = 0;

        assertEquals(Statement.createTable("trips").addColumns(Utils.tripsColumns).statement(), statements.get(index++));
        assertEquals(Statement.copy("trips", "trips.csv"), statements.get(index++));
        assertEquals(Statement.select("trips").where("distance", Comparison.GREATER_THAN, 100L).statement(), statements.get(index++));

        assertEquals(index, statements.size());
    }

    @Test
    void parsesNegativeNumbers() {
        SqlParser parser = new SqlParser();

        List<Statement> statements = parser.parse("""
                SELECT * FROM trips WHERE distance < -100;
                SELECT * FROM trips WHERE distance < -100.5;""");

        int index = 0;

        assertEquals(Statement.select("trips").where("distance", Comparison.LESS_THAN, -100L).statement(), statements.get(index++));
        assertEquals(Statement.select("trips").where("distance", Comparison.LESS_THAN, -100.5).statement(), statements.get(index++));

        assertEquals(index, statements.size());
    }

    @Test
    void parsesNumberLimits() {
        SqlParser parser = new SqlParser();

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

        assertEquals(Statement.select("trips").where("distance", Comparison.LESS_THAN,     Long.MAX_VALUE).statement(),    s0);
        assertEquals(Statement.select("trips").where("price",    Comparison.LESS_THAN,     Double.MAX_VALUE).statement(),  s1);
        assertEquals(Statement.select("trips").where("price",    Comparison.LESS_THAN,     Double.MIN_NORMAL).statement(), s2);
        assertEquals(Statement.select("trips").where("price",    Comparison.LESS_THAN,     Double.MIN_VALUE).statement(),  s3);
        assertEquals(Statement.select("trips").where("price",    Comparison.LESS_THAN,     0.0).statement(),               s4);
        assertEquals(Statement.select("trips").where("distance", Comparison.GREATER_THAN,  Long.MIN_VALUE).statement(),    s5);
        assertEquals(Statement.select("trips").where("price",    Comparison.GREATER_THAN, -Double.MAX_VALUE).statement(),  s6);
        assertEquals(Statement.select("trips").where("price",    Comparison.GREATER_THAN, -Double.MIN_NORMAL).statement(), s7);
        assertEquals(Statement.select("trips").where("price",    Comparison.GREATER_THAN, -Double.MIN_VALUE).statement(),  s8);
        assertEquals(Statement.select("trips").where("price",    Comparison.GREATER_THAN, -0.0).statement(),               s9);
    }

    @Test
    void throwsSqlParseExceptionOnUnrepresentableNumbers() {
        SqlParser parser = new SqlParser();

        // Integer too positive
        assertThrows(SqlParseException.class, () ->
                parser.parse("SELECT * FROM trips WHERE distance > 9223372036854775808;"));

        // Integer too negative
        assertThrows(SqlParseException.class, () ->
                parser.parse("SELECT * FROM trips WHERE distance < -9223372036854775809;"));

        // Double too positive
        assertThrows(SqlParseException.class, () ->
                parser.parse("SELECT * FROM trips WHERE price > 1179769313486231570814527423731704356798070567525844996598917476803157260780028538760589558632766878171540458953514382464234321326889464182768467546703537516986049910576551282076245490090389328944075868508455133942304583236903222948165808559332123348274797826204144723168738177180919299881250404026184124858368.0;"));

        // Double too negative
        assertThrows(SqlParseException.class, () ->
                parser.parse("SELECT * FROM trips WHERE price > -1179769313486231570814527423731704356798070567525844996598917476803157260780028538760589558632766878171540458953514382464234321326889464182768467546703537516986049910576551282076245490090389328944075868508455133942304583236903222948165808559332123348274797826204144723168738177180919299881250404026184124858368.0;"));
    }

    @Test
    void parsesSelectWithColumnList() {
        SqlParser parser = new SqlParser();

        List<Statement> statements = parser.parse("""
                SELECT city, price FROM trips;
                SELECT distance FROM trips WHERE distance > 100;""");

        int index = 0;

        assertEquals(Statement.select("trips").columns("city", "price").statement(), statements.get(index++));
        assertEquals(Statement.select("trips").columns("distance").where("distance", Comparison.GREATER_THAN, 100L).statement(), statements.get(index++));

        assertEquals(index, statements.size());
    }

    @Test
    void parsesSeletLimits() {
        SqlParser parser = new SqlParser();

        List<Statement> statements = parser.parse("""
                SELECT * FROM trips LIMIT 5;
                SELECT * FROM trips WHERE distance > 100 LIMIT -10;""");

        int index = 0;

        assertEquals(Statement.select("trips").limit(5).statement(), statements.get(index++));
        assertEquals(Statement.select("trips").where("distance", Comparison.GREATER_THAN, 100L).limit(-10).statement(), statements.get(index++));
    }
}
