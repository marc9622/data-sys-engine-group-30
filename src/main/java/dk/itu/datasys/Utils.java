package dk.itu.datasys;

import java.util.function.Supplier;

import org.slf4j.Logger;

public class Utils {
    private Utils() {}

    public static <T> T logExceptions(Logger logger, String context, Supplier<T> function) {
        try {
            return function.get();
        } catch (RuntimeException e) {
            String message = e.getMessage();
            if (message == null)
                logger.error(context);
            else
                logger.error(context + ": {}", message.replace(",", " "));
            throw e;
        }
    }

    public static void logExceptions(Logger logger, String context, Runnable function) {
        logExceptions(logger, context, () -> { function.run(); return null; });
    }

}

