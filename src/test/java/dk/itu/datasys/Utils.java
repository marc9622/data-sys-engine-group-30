package dk.itu.datasys;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import dk.itu.datasys.Spec.*;
import dk.itu.datasys.StorageEngine.MalformedCsvException;

public final class Utils {
    private Utils() {}

    public static final String tripsName = "trips";

    public static final List<ColumnSpec> tripsColumns = List.of(
            new ColumnSpec("city", ColumnType.STRING),
            new ColumnSpec("distance", ColumnType.LONG),
            new ColumnSpec("price", ColumnType.DOUBLE));

    public static final List<Object[]> tripsRows = parseCsvFile(resource("trips.csv"));

    public static Path resource(String name) {
        return Path.of("src/test/resources/" + name).toAbsolutePath();
    }

    public static List<Object[]> parseCsvFile(Path path) {
        List<Object[]> rows = new ArrayList<>();

        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.US_ASCII)) {
            String line;
            while ((line = reader.readLine()) != null) {
                try {
                    rows.add(StorageEngine.parseCsvLine(line, tripsColumns));
                } catch (MalformedCsvException e) {
                    throw e.toRuntimeException(path.toString(), rows.size());
                }
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        return rows;
    }

}

