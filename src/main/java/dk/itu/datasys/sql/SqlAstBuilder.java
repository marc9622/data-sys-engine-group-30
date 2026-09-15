package dk.itu.datasys.sql;

import java.util.List;
import java.util.Optional;

import dk.itu.datasys.Spec.ColumnSpec;
import dk.itu.datasys.Spec.ColumnType;
import dk.itu.datasys.Spec.Comparison;
import dk.itu.datasys.Statement;

public final class SqlAstBuilder extends SqlBaseVisitor<Object> {
    // Builds an AST statement list from the complete SQL script.
    @Override
    public Object visitScript(SqlParser.ScriptContext context) {
        return context.statement().stream()
                .map(statement -> (Statement) visit(statement))
                .toList();
    }

    // Builds a CREATE TABLE node and its column definitions.
    @Override
    public Object visitCreateTable(SqlParser.CreateTableContext context) {
        List<ColumnSpec> columns = context.columnDef().stream()
                .map(column -> (ColumnSpec) visit(column))
                .toList();

        return new Statement.CreateTable(
                context.IDENTIFIER().getText(),
                columns);
    }

    // Converts one column definition into a ColumnSpec.
    @Override
    public Object visitColumnDef(SqlParser.ColumnDefContext context) {
        return new ColumnSpec(
                context.IDENTIFIER().getText(),
                (ColumnType) visit(context.columnType()));
    }

    // Maps the SQL column type token to the storage type enum.
    @Override
    public Object visitColumnType(SqlParser.ColumnTypeContext context) {
        if (context.STRING() != null) {
            return ColumnType.STRING;
        }
        if (context.LONG() != null) {
            return ColumnType.LONG;
        }
        return ColumnType.DOUBLE;
    }

    // Builds a COPY node and removes quotes from the file path.
    @Override
    public Object visitCopy(SqlParser.CopyContext context) {
        return new Statement.Copy(
                context.IDENTIFIER().getText(),
                unquote(context.STRING_LITERAL().getText()));
    }

    // Builds a SELECT node with an optional WHERE predicate.
    @Override
    public Object visitSelect(SqlParser.SelectContext context) {
        Optional<Statement.Select.Predicate> predicate = context.predicate() == null
                ? Optional.empty()
                : Optional.of((Statement.Select.Predicate) visit(context.predicate()));

        return new Statement.Select(context.IDENTIFIER().getText(), predicate);
    }

    // Builds a predicate from its column, operator, and literal.
    @Override
    public Object visitPredicate(SqlParser.PredicateContext context) {
        return new Statement.Select.Predicate(
                context.IDENTIFIER().getText(),
                comparison(context.comparison.getText()),
                visit(context.literal()));
    }

    // Converts a SQL literal to String, Long, or Double.
    @Override
    public Object visitLiteral(SqlParser.LiteralContext context) {
        if (context.STRING_LITERAL() != null) {
            return unquote(context.STRING_LITERAL().getText());
        }
        if (context.LONG_LITERAL() != null) {
            return Long.valueOf(context.LONG_LITERAL().getText());
        }
        return Double.valueOf(context.DOUBLE_LITERAL().getText());
    }

    // Maps a SQL comparison operator to the Comparison enum.
    private static Comparison comparison(String operator) {
        return switch (operator) {
            case "=" -> Comparison.EQUALS;
            case "<" -> Comparison.LESS_THAN;
            case ">" -> Comparison.GREATER_THAN;
            default -> throw new IllegalArgumentException("Unknown comparison: " + operator);
        };
    }

    // Removes the surrounding single quotes from a SQL string literal.
    private static String unquote(String literal) {
        return literal.substring(1, literal.length() - 1);
    }
}
