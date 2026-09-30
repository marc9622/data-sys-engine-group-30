package dk.itu.datasys;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

public final class Engine {
    public static void main(String[] args) {
        try {
            run(args);
        } catch (RuntimeException | IOException e) {
            System.err.println("error: " + e.getMessage());
            System.exit(1);
        }
    }

    private static void run(String[] args) throws IOException {
        if (args.length == 0) {
            printUsage();
            return;
        }

        String sql;
        if (args.length == 1) {
            sql = ensureStatementTerminator(args[0]);
        } else if (args.length == 2 && args[0].equals("-f")) {
            sql = Files.readString(Path.of(args[1]));
        } else {
            throw new IllegalArgumentException("usage: Engine [SQL] | Engine -f script.sql");
        }

        Executor executor = new Executor(new StorageEngine(Path.of("data")));
        for (List<Object[]> statementRows : executor.executeScript(sql)) {
            for (Object[] row : statementRows)
                System.out.println(csvRow(row));
        }
    }

    private static void printUsage() {
        System.out.println("Data Systems Group 30");
        System.out.println("Usage: mvn -q compile exec:java -Dexec.args=\"'SELECT * FROM trips'\"");
        System.out.println("       mvn -q compile exec:java -Dexec.args=\"-f script.sql\"");
    }

    private static String ensureStatementTerminator(String sql) {
        String trimmed = sql.stripTrailing();
        return trimmed.endsWith(";") ? sql : trimmed + ";";
    }

    private static String csvRow(Object[] row) {
        return Arrays.stream(row)
                .map(Engine::csvValue)
                .reduce((left, right) -> left + "," + right)
                .orElse("");
    }

    private static String csvValue(Object value) {
        if (value == null)
            return "";

        String text = value.toString();
        if (text.indexOf(',') >= 0 || text.indexOf('"') >= 0 || text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0)
            return "\"" + text.replace("\"", "\"\"") + "\"";
        return text;
    }
}
