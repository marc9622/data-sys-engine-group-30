package dk.itu.datasys;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;

public final class Engine {
    public static void main(String[] args) {
        try {
            switch (args.length) {
                case 0 -> {
                    System.out.println("Data Systems Group 30");
                    printUsage();
                }
                default -> run(Arrays.asList(args).iterator());
            }
        } catch (RuntimeException | IOException e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(1);
        }
    }

    private static void run(Iterator<String> args) throws IOException {
        boolean queryStatementSupplied = false;
        boolean queryFileSupplied = false;

        String sql = "";
        while (args.hasNext()) {
            switch (args.next()) {
                case "-f" -> {
                    if (queryFileSupplied) {
                        System.out.println("Only one `-f` argument is allowed per command");
                        printUsage();
                        System.exit(1);
                    }

                    queryFileSupplied = true;

                    if (!args.hasNext()) {
                        System.out.println("Missing file path after `-f`");
                        printUsage();
                        System.exit(1);
                    }

                    Path filePath = Path.of(args.next());

                    try {
                        sql = Files.readString(filePath);
                    }
                    catch (NoSuchFileException e) {
                        System.out.println("Unknown file `" + filePath + "`");
                        System.exit(1);
                    }
                }
                case String query -> {
                    queryStatementSupplied = true;

                    sql += " " + query;
                }
            }

            if (queryStatementSupplied && queryFileSupplied) {
                System.out.println("Cannot specify both a command line query and a query file");
                System.exit(1);
            }
        }

        if (queryStatementSupplied) {
            sql = sql.stripTrailing();
            if (!sql.endsWith(";"))
                sql = sql + ";";
        }

        Executor executor = new Executor(new StorageEngine(Path.of("data")));
        for (List<Object[]> statementRows : executor.executeScript(sql)) {
            for (Object[] row : statementRows)
                System.out.println(csvRow(row));
        }
    }

    private static void printUsage() {
        System.out.println("Usage: ./engine \"SELECT * FROM trips\"");
        System.out.println("       ./engine -f script.sql");
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
        if (text.contains(",") || text.contains("\"") || text.contains("\n") || text.contains("\r"))
            return "\"" + text.replace("\"", "\"\"") + "\"";

        return text;
    }
}
