package dk.itu.datasys;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import dk.itu.datasys.Spec.ColumnSpec;
import dk.itu.datasys.Spec.ColumnType;
import dk.itu.datasys.Spec.Comparison;

public final class Engine {
    private static final Logger LOGGER = LoggerFactory.getLogger(Engine.class);

    public static void main(String[] args) throws Exception {
        MDC.put("sessionId", UUID.randomUUID().toString());
        MDC.put("statementNumber", "0");
        LOGGER.debug("engine started");

        Path csv = Path.of("src/test/resources/trips.csv");
        if (!Files.exists(csv))
            throw new IllegalArgumentException("golden CSV not found: " + csv.toAbsolutePath());

        Path dataDirectory = Files.createTempDirectory("datasys-demo");
        StorageEngine storage = new StorageEngine(dataDirectory);
        storage.createTable("trips", List.of(
                new ColumnSpec("city", ColumnType.STRING),
                new ColumnSpec("distance", ColumnType.LONG),
                new ColumnSpec("price", ColumnType.DOUBLE)));
        storage.copyFromCsvFile("trips", csv.toString());

        printResults("distance GREATER_THAN 100", storage.select("trips", "distance",
                Comparison.GREATER_THAN, 100L));
        printResults("city EQUALS Copenhagen", storage.select("trips", "city",
                Comparison.EQUALS, "Copenhagen"));
        printResults("price LESS_THAN 50.0", storage.select("trips", "price",
                Comparison.LESS_THAN, 50.0));

        LOGGER.debug("engine stopped");
    }

    private static void printResults(String predicate, List<Object[]> rows) {
        System.out.println(predicate + ":" + "("  + rows.size() + " rows)");
        for (Object[] row : rows)
            System.out.println(Arrays.toString(row));
    }
}
