package cn.edu.campus.approval.g5.api;

import java.util.List;
import java.util.UUID;

public final class AuthResponses {
    private AuthResponses() { }
    public record Ok<T>(T data, UUID traceId) { }
    public record Field(String field, String reason) { }
    public record Details(List<Field> fields, List<Object> conflicts, String currentVersion,
                          UUID operationId, Integer retryAfterSeconds) { }
    public record Error(String code, String message, UUID traceId, Details details) { }
    public static Error error(IdentityException failure, UUID trace) {
        return new Error(failure.code(), failure.getMessage(), trace,
                new Details(failure.field() == null ? List.of() : List.of(new Field(failure.field(), failure.getMessage())),
                        List.of(), failure.currentVersion(), null, failure.retryAfterSeconds()));
    }
}
