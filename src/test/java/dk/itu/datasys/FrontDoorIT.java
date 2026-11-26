package dk.itu.datasys;

import static dk.itu.datasys.TestingUtils.*;
import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FrontDoorIT {
    @Test
    void scriptWritesOnlyCsvRowsToStdout(@TempDir Path tmp) throws IOException, InterruptedException {
        Path script = tmp.resolve("query.sql");
        Files.writeString(script, """
                CREATE TABLE trips (city STRING, distance LONG, price DOUBLE);
                COPY trips FROM '%s';
                SELECT * FROM trips WHERE city = 'Odense';
                SELECT city, price FROM trips WHERE city = 'Odense';
                """.formatted(resource("trips.csv").toString())); 

        EngineResult result = runEngine(tmp, script);
        if (result.exitCode() != 0)
            throw new AssertionError("Process exited with code " + result.exitCode() + ", stderr: " + result.stderr());

        String stdout = result.stdout();
        String[] lines = stdout.split(System.lineSeparator());

        assertEquals(2, lines.length);
        assertEquals("Odense,95,120.75", lines[0]);
        assertEquals("Odense,120.75", lines[1]);
    }

    @Test
    void deleteScriptRunsThroughEngineExecutable(@TempDir Path tmp) throws IOException, InterruptedException {
        Path script = tmp.resolve("delete.sql");
        Files.writeString(script, """
                CREATE TABLE trips (city STRING, distance LONG, price DOUBLE);
                COPY trips FROM '%s';
                COPY trips FROM '%s';
                DELETE FROM trips WHERE city = 'Copenhagen';
                SELECT * FROM trips;
                """.formatted(resource("trips.csv"), resource("trips.csv")));

        EngineResult result = runEngine(tmp, script);
        if (result.exitCode() != 0)
            throw new AssertionError("Process exited with code " + result.exitCode() + ", stderr: " + result.stderr());
        
        String stdout = result.stdout();
        String[] lines = stdout.split(System.lineSeparator());
     
        List<String> expected = tripsRows.stream()
                .filter(row -> !row[0].equals("Copenhagen"))
                .map(row -> "%s,%s,%s".formatted(row[0], row[1], row[2]))
                .flatMap(row -> Stream.of(row, row))
                .sorted()
                .toList();

        List<String> actual = Stream.of(lines)
                .sorted()
                .toList();
        
        assertEquals(expected, actual);
    }

    @Test
    void failingScriptLeavesStdoutEmptyAndReportsOnStderr(@TempDir Path tmp) throws IOException, InterruptedException {
        Path script = tmp.resolve("failing.sql");
        Files.writeString(script, "SELECT * FROM missing;");

        EngineResult result = runEngine(tmp, script);

        assertNotEquals(0, result.exitCode());
        assertEquals("", result.stdout());
        assertTrue(result.stderr().contains("Error:"));
    }

    @Test
    void compareJoinQueriesWithDuckDB(@TempDir Path tmp) throws IOException, InterruptedException {
        Path script = tmp.resolve("join.sql");
        Files.writeString(script, """
                CREATE TABLE cities (ct_city STRING, ct_country STRING, ct_population LONG);
                COPY cities FROM '%s';
                CREATE TABLE customers (c_name STRING, c_city STRING);
                COPY customers FROM '%s';
                CREATE TABLE orders (o_orderkey LONG, o_custname STRING, o_prodname STRING, o_quantity LONG);
                COPY orders FROM '%s';
                CREATE TABLE products (p_name STRING, p_price DOUBLE);
                COPY products FROM '%s';

                SELECT * FROM orders, customers
                WHERE o_custname = c_name;

                SELECT * FROM orders, customers, products
                WHERE o_custname = c_name AND o_prodname = p_name;

                SELECT * FROM orders, customers, products, cities
                WHERE o_custname = c_name AND o_prodname = p_name AND c_city = ct_city;

                SELECT * FROM orders, customers, products
                WHERE o_custname = c_name AND o_prodname = p_name AND c_city = 'Copenhagen';
                """.formatted(resource("cities.csv"), resource("customers.csv"), resource("orders.csv"), resource("products.csv")));

        Path scriptDuckDB = tmp.resolve("join-duckdb.sql");
        Files.writeString(scriptDuckDB, """
                CREATE TABLE cities (ct_city STRING, ct_country STRING, ct_population LONG);
                COPY cities FROM '%s' (HEADER false);
                CREATE TABLE customers (c_name STRING, c_city STRING);
                COPY customers FROM '%s' (HEADER false);
                CREATE TABLE orders (o_orderkey LONG, o_custname STRING, o_prodname STRING, o_quantity LONG);
                COPY orders FROM '%s' (HEADER false);
                CREATE TABLE products (p_name STRING, p_price DOUBLE);
                COPY products FROM '%s' (HEADER false);

                SELECT * FROM orders, customers
                WHERE o_custname = c_name;

                SELECT * FROM orders, customers, products
                WHERE o_custname = c_name AND o_prodname = p_name;

                SELECT * FROM orders, customers, products, cities
                WHERE o_custname = c_name AND o_prodname = p_name AND c_city = ct_city;

                SELECT * FROM orders, customers, products
                WHERE o_custname = c_name AND o_prodname = p_name AND c_city = 'Copenhagen';
                """.formatted(resource("cities.csv"), resource("customers.csv"), resource("orders.csv"), resource("products.csv")));

        EngineResult engineResult = runEngine(tmp, script);
        EngineResult duckdbResult = runDuckDB(tmp, scriptDuckDB);

        assertEquals(0, engineResult.exitCode(), "Engine process failed, stderr: " + engineResult.stderr());
        assertEquals(0, duckdbResult.exitCode(), "DuckDB process failed, stderr: " + duckdbResult.stderr());

        List<String> engineLines = Stream.of(engineResult.stdout().split(System.lineSeparator()))
                .sorted()
                .toList();
        List<String> duckdbLines = Stream.of(duckdbResult.stdout().split(System.lineSeparator()))
                .sorted()
                .toList();

        assertEquals(duckdbLines, engineLines);
    }

    private record EngineResult(int exitCode, String stdout, String stderr) {}
    private static EngineResult runEngine(Path workingDirectory, Path script)
            throws IOException, InterruptedException {
        Path stdout = workingDirectory.resolve("stdout.txt");
        Path stderr = workingDirectory.resolve("stderr.txt");
        String javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Process process = new ProcessBuilder(
                javaExecutable,
                "-cp", System.getProperty("java.class.path"),
                "dk.itu.datasys.Engine",
                "-f", script.toString())
                .directory(workingDirectory.toFile())
                .redirectOutput(stdout.toFile())
                .redirectError(stderr.toFile())
                .start();

        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly().waitFor();
            throw new AssertionError("Engine process timed out after 30 seconds, stderr: "
                    + Files.readString(stderr, StandardCharsets.UTF_8));
        }

        int exitCode = process.exitValue();
        return new EngineResult(exitCode,
                Files.readString(stdout, StandardCharsets.UTF_8),
                Files.readString(stderr, StandardCharsets.UTF_8));
    }

    private static EngineResult runDuckDB(Path workingDirectory, Path script) throws IOException, InterruptedException {
        Path stdout = workingDirectory.resolve("duckdb_stdout.txt");
        Path stderr = workingDirectory.resolve("duckdb_stderr.txt");
        Process process = new ProcessBuilder(
                "duckdb",
                "-csv", "-noheader",
                "-f", script.toString())
                .directory(workingDirectory.toFile())
                .redirectOutput(stdout.toFile())
                .redirectError(stderr.toFile())
                .start();

        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly().waitFor();
            throw new AssertionError("DuckDB process timed out after 30 seconds, stderr: "
                    + Files.readString(stderr, StandardCharsets.UTF_8));
        }

        int exitCode = process.exitValue();
        return new EngineResult(exitCode,
                Files.readString(stdout, StandardCharsets.UTF_8),
                Files.readString(stderr, StandardCharsets.UTF_8));
    }
}
