package dk.itu.datasys;

import java.util.List;
import java.util.Objects;

import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dk.itu.datasys.sql.SqlAstBuilder;
import dk.itu.datasys.sql.SqlLexer;
import dk.itu.datasys.sql.SqlParser;

public final class SqlParserFacade {
    private static final Logger LOGGER = LoggerFactory.getLogger(SqlParser.class);

    public List<Statement> parse(String sqlText) {
        Objects.requireNonNull(sqlText, "sqlText");  

        long start = System.currentTimeMillis(); // this line is for measuring the duration of the parsing process
        try {
            SqlLexer lexer = new SqlLexer(CharStreams.fromString(sqlText));
            lexer.removeErrorListeners();
            lexer.addErrorListener(ThrowingErrorListener.INSTANCE);

            CommonTokenStream tokens = new CommonTokenStream(lexer);
            SqlParser parser = new SqlParser(tokens);
            parser.removeErrorListeners();
            parser.addErrorListener(ThrowingErrorListener.INSTANCE);

            SqlParser.ScriptContext script = parser.script(); 
            @SuppressWarnings("unchecked")
            List<Statement> statements = (List<Statement>) new SqlAstBuilder().visit(script);

            long durationMs = System.currentTimeMillis() - start;
            LOGGER.debug("statements={} durationMs={}", statements.size(), durationMs);
            return statements;
        } catch (SqlParseException e) {
            long durationMs = System.currentTimeMillis() - start;
            LOGGER.error("failed line={} col={} durationMs={}", e.line(), e.column(), durationMs);
            throw e;
        }
    }

    private static final class ThrowingErrorListener extends BaseErrorListener {
        private static final ThrowingErrorListener INSTANCE = new ThrowingErrorListener();

        @Override
        public void syntaxError(Recognizer<?, ?> recognizer,
                                Object offendingSymbol,
                                int line,
                                int charPositionInLine,
                                String msg,
                                RecognitionException e) {
            throw new SqlParseException("line " + line + ":" + charPositionInLine + " " + msg, line, charPositionInLine);
        }
    }
}
