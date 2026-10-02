package dk.itu.datasys;

import java.util.UUID;

import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.slf4j.MDC;

public class GlobalLoggingExtension implements BeforeAllCallback {
    private static boolean started = false;

    @Override
    public void beforeAll(ExtensionContext context) {
        if (!started) {
            started = true;

            // To make test sessions more recognizable in logs, we generate a session ID that ends with "AAAAAAAAAAAA" instead of a random suffix.
            // E.g. 12345678-ABCD-1234-ABCD-AAAAAAAAAAAA
            String sessionId = UUID.randomUUID().toString();
            sessionId = sessionId.substring(0, sessionId.length() - 12);
            sessionId += "AAAAAAAAAAAA";
            MDC.put("sessionId", sessionId);
            MDC.put("statementNumber", "0");
        }
    }
}
