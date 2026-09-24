package dk.itu.datasys;

import org.antlr.v4.runtime.Token;

public final class SqlParseException extends RuntimeException {
    private final int line;
    private final int column;

    public SqlParseException(Throwable cause, int line, int column) {
        super("Invalid SQL at line " + line + ":" + column, cause);
        this.line = line;
        this.column = column;
    }

    public SqlParseException(Throwable cause, Token token) {
        this(cause, token.getLine(), token.getCharPositionInLine());
    }

    public SqlParseException(String message, int line, int column) {
        super("Invalid SQL at line " + line + ":" + column + " " + message);
        this.line = line;
        this.column = column;
    }

    public SqlParseException(String message, Token token) {
        this(message, token.getLine(), token.getCharPositionInLine());
    }

    public int line() {
        return line;
    }

    public int column() {
        return column;
    }
}
