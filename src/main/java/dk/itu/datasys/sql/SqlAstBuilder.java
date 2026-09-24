package dk.itu.datasys.sql;

import java.util.List;
import java.util.Optional;

import org.antlr.v4.runtime.tree.TerminalNode;

import dk.itu.datasys.Spec.*;
import dk.itu.datasys.SqlParseException;
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
        TerminalNode literal;
        if ((literal = context.STRING_LITERAL()) != null) {
            return unquote(literal.getText());
        }
        try {
            if ((literal = context.LONG_LITERAL()) != null) {
                return Long.valueOf(literal.getText());
            }
            if ((literal = context.DOUBLE_LITERAL()) != null) {
                double value = Double.valueOf(literal.getText());
                if (!Double.isFinite(value))
                    throw new SqlParseException("Double literal `" + literal.getText() + "` out of range: ", literal.getSymbol());
                return value;
            }
        } catch (NumberFormatException e) {
            throw new SqlParseException(e, literal.getSymbol());
        }
        throw new IllegalArgumentException("Unknown literal: `" + context.getText() + "`");
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
