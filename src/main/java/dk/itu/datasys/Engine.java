package dk.itu.datasys;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Scanner;

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
        enum CliParseState {
            EMPTY,
            QUERY_STATEMENT,
            QUERY_FILE,
        }

        CliParseState cliParseState = CliParseState.EMPTY;

        boolean cliDeleteAllData = false;

        String sql = "";
        while (args.hasNext()) {
            cliParseState = switch (args.next()) {
                case "-f" -> {
                    switch (cliParseState) {
                        case EMPTY -> {}
                        case QUERY_STATEMENT -> {
                            System.out.println("Cannot specify `-f` together with a `-c` query statement");
                            printUsage();
                            System.exit(1);
                        }
                        case QUERY_FILE -> {
                            System.out.println("Only one `-f` argument is allowed per command");
                            printUsage();
                            System.exit(1);
                        }
                    };

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

                    yield CliParseState.QUERY_FILE;
                }
                case "-c" -> {
                    switch (cliParseState) {
                        case EMPTY -> {}
                        case QUERY_STATEMENT -> {
                            System.out.println("Only one `-c` argument is allowed per command");
                            printUsage();
                            System.exit(1);
                        }
                        case QUERY_FILE -> {
                            System.out.println("Cannot specify `-c` together with a `-f` query file");
                            printUsage();
                            System.exit(1);
                        }
                    }

                    yield CliParseState.QUERY_STATEMENT;
                }
                case "--delete-all-data" -> {
                    if (cliDeleteAllData) {
                        System.out.println("Only one `--delete-all-data` argument is allowed per command");
                        printUsage();
                        System.exit(1);
                    }

                    cliDeleteAllData = true;

                    yield cliParseState;
                }
                case String arg -> {
                    switch (cliParseState) {
                        case EMPTY, QUERY_FILE -> {
                            System.out.println("Unknown argument `" + arg + "`");
                            printUsage();
                            System.exit(1);
                        }
                        case QUERY_STATEMENT -> {
                            sql += " " + arg;
                        }
                    }

                    yield CliParseState.QUERY_STATEMENT;
                }
            };
        }

        if (cliParseState == CliParseState.QUERY_STATEMENT) {
            sql = sql.stripTrailing();
            if (!sql.endsWith(";"))
                sql += ";";
        }

        Path dataDir = Path.of("data");

        if (cliDeleteAllData) {
            System.out.println("Deleting all database files in `" + dataDir + "`");

            StorageEngine.deleteAllData(dataDir, files -> {
                if (files.isEmpty()) {
                    System.out.println("Nothing to delete; no database files found");
                    return false;
                }

                System.out.println("The following files will be deleted:");
                for (String fileName : files)
                    System.out.println("\t" + fileName);
                System.out.print("Are you sure you want to delete them? (y/n): ");

                Scanner scanner = new Scanner(System.in);
                String input = scanner.nextLine().trim().toLowerCase();

                boolean confirm = input.equals("y");

                scanner.close();
                return confirm;
            });
        }

        if (cliParseState == CliParseState.EMPTY)
            return;

        StorageEngine storageEngine = new StorageEngine(dataDir);
        Executor executor = new Executor(storageEngine);

        for (List<Object[]> statementRows : executor.executeScript(sql)) {
            for (Object[] row : statementRows)
                System.out.println(csvRow(row));
        }
    }

    private static void printUsage() {
        System.out.println("Usage: ./engine -c \"SELECT * FROM trips\"");
        System.out.println("       ./engine -f script.sql");
        System.out.println("       ./engine --delete-all-data\t# WARNING: deletes all database files before running queries");
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
