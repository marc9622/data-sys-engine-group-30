package dk.itu.datasys;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

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
                """.formatted(Utils.resource("trips.csv").toString())); 

        String javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java").toString(); 
        Process process = new ProcessBuilder(
            javaExecutable,
            "-cp", System.getProperty("java.class.path"),
            "dk.itu.datasys.Engine",
            "-f", script.toString())
            .directory(tmp.toFile())
            .start();

        int exitCode = process.waitFor();
        assertEquals(0, exitCode);

        String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String[] lines = stdout.split(System.lineSeparator());

        assertEquals(2, lines.length);
        assertEquals("Odense,95,120.75", lines[0]);
        assertEquals("Odense,120.75", lines[1]);
    }

    @Test
    void failingScriptLeavesStdoutEmptyAndReportsOnStderr(@TempDir Path tmp) throws IOException, InterruptedException {
        Path script = tmp.resolve("failing.sql");
        Files.writeString(script, "SELECT * FROM missing;");

        String javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java").toString(); 
        Process process = new ProcessBuilder(
            javaExecutable,
            "-cp", System.getProperty("java.class.path"),
            "dk.itu.datasys.Engine",
            "-f", script.toString())
            .directory(tmp.toFile())
            .start();

        int exitCode = process.waitFor();
        String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);

        assertNotEquals(0, exitCode);
        assertEquals("", stdout);
        assertTrue(stderr.contains("Error:"));
    }
}
