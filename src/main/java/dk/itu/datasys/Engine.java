package dk.itu.datasys;

import java.nio.file.Path;
import java.util.List;

import dk.itu.datasys.Spec.ColumnSpec;
import dk.itu.datasys.Spec.ColumnType;

public final class Engine {
    public static void main(String[] args) {
    System.setProperty("maxRowsPerPartition", "2");

    Path dataDir = Path.of("data");
    StorageEngine storage = new StorageEngine(dataDir);
    List<ColumnSpec> columns = List.of(
        new ColumnSpec("city", ColumnType.STRING),
        new ColumnSpec("distance", ColumnType.LONG),
        new ColumnSpec("price", ColumnType.DOUBLE));

    storage.createTable("trips", columns);
    storage.copyFromCsvFile("trips", "src/test/resources/trips.csv");

    System.out.println("Generated catalog: " + dataDir.resolve("catalog.json"));
    System.out.println("Generated partition files in: " + dataDir);
    }
}
