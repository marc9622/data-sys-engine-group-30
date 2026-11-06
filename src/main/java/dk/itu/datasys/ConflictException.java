package dk.itu.datasys;

public final class ConflictException extends RuntimeException {
    public ConflictException(String tableName, int baseVersion, int currentVersion) {
        super("conflict table=" + tableName + " base=" + baseVersion + " current=" + currentVersion);
    }
}
