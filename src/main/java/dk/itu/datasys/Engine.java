package dk.itu.datasys;

import java.util.List;
import java.util.Optional;

import dk.itu.datasys.Spec.ColumnSpec;
import dk.itu.datasys.Spec.ColumnType;
import dk.itu.datasys.Spec.Comparison;

public final class Engine {
    public static void main(String[] args) {
        SqlPrinter printer = new SqlPrinter();

        List<Statement> statements = List.of(
                new Statement.CreateTable("trips", List.of(
                        new ColumnSpec("city", ColumnType.STRING),
                        new ColumnSpec("distance", ColumnType.LONG),
                        new ColumnSpec("price", ColumnType.DOUBLE))),
                new Statement.Copy("trips", "trips.csv"),
                new Statement.Select("trips",
                        Optional.of(new Statement.Select.Predicate("distance", Comparison.GREATER_THAN, 100L))),
                new Statement.Select("trips", Optional.empty())
        );

        for (Statement statement : statements) {
            System.out.println(printer.print(statement));
        }
    }
}
