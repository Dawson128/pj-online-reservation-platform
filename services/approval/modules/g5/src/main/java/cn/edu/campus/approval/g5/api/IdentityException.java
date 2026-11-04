package cn.edu.campus.approval.g5.api;

public final class IdentityException extends RuntimeException {
    private final int status;
    private final String code;
    private final String field;
    private final String currentVersion;
    private final Integer retryAfterSeconds;

    public IdentityException(int status, String code, String message) {
        this(status, code, message, null, null, null);
    }

    public IdentityException(int status, String code, String message, String field,
                             String currentVersion, Integer retryAfterSeconds) {
        super(message);
        this.status = status;
        this.code = code;
        this.field = field;
        this.currentVersion = currentVersion;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public int status() { return status; }
    public String code() { return code; }
    public String field() { return field; }
    public String currentVersion() { return currentVersion; }
    public Integer retryAfterSeconds() { return retryAfterSeconds; }
}
