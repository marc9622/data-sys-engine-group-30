package dk.itu.datasys;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.slf4j.Logger;

import dk.itu.datasys.Spec.*;
import dk.itu.datasys.StorageEngine.ParseCsvLineResult;

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
                Object[] parsed = switch (StorageEngine.parseCsvLine(line, tripsColumns)) {
                    case ParseCsvLineResult.Success(Object[] row) -> row;
                    case ParseCsvLineResult.Malformed m ->
                        throw m.toRuntimeException(path.toString(), rows.size());
                };

                rows.add(parsed);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        return rows;
    }

    public static class Streams {
        private Streams() {}

        public static <T> Stream<Set<T>> powerSetOf(Collection<T> src) {
            List<T> list = new ArrayList<>(src);
            int powerSetSize = 1 << list.size(); // 2^n

            return IntStream
                .range(0, powerSetSize)
                .mapToObj(i -> IntStream
                    .range(0, list.size())
                    .filter(j -> ((i >> j) & 1) == 1)
                    .mapToObj(list::get)
                    .collect(Collectors.toSet())
                );
        }

        public static <T> Stream<List<T>> permutationsOf(Collection<T> src) {
            if (src.isEmpty())
                return Stream.of(List.of());

            List<T> list = new ArrayList<>(src);

            return IntStream
                .range(0, list.size())
                .boxed()
                .flatMap(i -> {
                    T head = list.get(i);
                    List<T> tail = new ArrayList<>(list);
                    tail.remove((int) i);
                    return permutationsOf(tail).map(perm -> {
                        List<T> result = new ArrayList<>();
                        result.add(head);
                        result.addAll(perm);
                        return result;
                    });
                });
        }
    }
}

