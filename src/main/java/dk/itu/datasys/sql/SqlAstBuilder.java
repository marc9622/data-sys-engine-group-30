package dk.itu.datasys.sql;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.antlr.v4.runtime.tree.TerminalNode;

import dk.itu.datasys.Spec.*;
import dk.itu.datasys.SqlParseException;
import dk.itu.datasys.Statement;

public final class SqlAstBuilder extends SqlBaseVisitor<Object> {
    // Builds an AST statement list from the complete SQL script.
    @Override
    public List<Statement> visitScript(SqlParser.ScriptContext context) {
        return context.statement().stream()
                .map(statement -> (Statement) visit(statement))
                .toList();
    }

    // Builds a CREATE TABLE node and its column definitions.
    @Override
    public Statement.CreateTable visitCreateTable(SqlParser.CreateTableContext context) {
        List<ColumnSpec> columns = context.columnDef().stream()
            .map(column -> visitColumnDef(column))
            .toList();

        return Statement
            .createTable(context.IDENTIFIER().getText())
            .addColumns(columns)
            .statement();
    }

    // Converts one column definition into a ColumnSpec.
    @Override
    public ColumnSpec visitColumnDef(SqlParser.ColumnDefContext context) {
        return new ColumnSpec(
                context.IDENTIFIER().getText(),
                visitColumnType(context.columnType()));
    }

    // Maps the SQL column type token to the storage type enum.
    @Override
    public ColumnType visitColumnType(SqlParser.ColumnTypeContext context) {
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
    public Statement.Copy visitCopy(SqlParser.CopyContext context) {
        return Statement.copy(
                context.IDENTIFIER().getText(),
                unquote(context.STRING_LITERAL().getText()));
    }

    // Builds a SELECT node.
    @Override
    public Statement.Select visitSelect(SqlParser.SelectContext context) {
        List<String> columnList = context.columnList() == null
                ? List.of()
                : visitColumnList(context.columnList());

        List<String> tableList = context.IDENTIFIER().stream()
            .map(table -> table.getText())
            .toList();

        List<Statement.Predicate> predicates = visitCondition(context.condition());

        List<Statement.FilterPredicate> filters = new ArrayList<>();
        List<Statement.JoinPredicate> joins = new ArrayList<>();
        for (Statement.Predicate predicate : predicates) {
            switch (predicate) {
                case Statement.FilterPredicate filter -> filters.add(filter);
                case Statement.JoinPredicate join -> joins.add(join);
            }
        }

        Optional<Integer> limit = Optional
            .ofNullable(context.limit())
            .map(t -> Integer.valueOf(t.getText()));

        return Statement
            .select(tableList)
            .columns(columnList)
            .where(filters)
            .joinOn(joins)
            .limit(limit)
            .statement();
    }

    // Builds a DELETE node.
    @Override
    public Statement.Delete visitDelete(SqlParser.DeleteContext context) {
        Optional<Statement.FilterPredicate> predicate = Optional
            .ofNullable(context.filter())
            .map(p -> visitFilter(p));

        return Statement
            .delete(context.IDENTIFIER().getText())
            .where(predicate)
            .statement();
    }

    // Builds the projection column list.
    @Override
    public List<String> visitColumnList(SqlParser.ColumnListContext context) {
        return context.IDENTIFIER().stream()
            .<String>map(column -> column.getText())
            .toList();
    }

    // Builds the list of predicates in the WHERE clause.
    @Override
    public List<Statement.Predicate> visitCondition(SqlParser.ConditionContext context) {
        return context.predicate().stream()
            .map(predicate -> (Statement.Predicate) (
                predicate.filter() != null
                ? visitFilter(predicate.filter())
                : visitJoin(predicate.join())
            ))
            .toList();
    }

    // Builds a filter predicate from its column, operator, and literal.
    @Override
    public Statement.FilterPredicate visitFilter(SqlParser.FilterContext context) {
        return new Statement.FilterPredicate(
                context.IDENTIFIER().getText(),
                comparison(context.comparison.getText()),
                visit(context.literal()));
    }

    // Builds a join on predicate from its two columns.
    @Override
    public Statement.JoinPredicate visitJoin(SqlParser.JoinContext context) {
        return new Statement.JoinPredicate(
                context.left.getText(),
                context.right.getText());
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
