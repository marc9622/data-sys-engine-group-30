CREATE TABLE logs (timestamp STRING, sessionId STRING, statementNumber LONG, threadId LONG,
                   logLevel STRING, className STRING, logMessage STRING);
COPY logs FROM 'logs/engine.log';
