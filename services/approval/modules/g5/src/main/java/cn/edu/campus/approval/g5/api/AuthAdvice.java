package cn.edu.campus.approval.g5.api;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.TransactionException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import cn.edu.campus.approval.g5.application.IdentityService;

@org.springframework.context.annotation.Profile("!shell")
@RestControllerAdvice(assignableTypes = {AuthController.class, ManagementController.class, ConfigurationController.class, ResourceFactsController.class, BusinessAuthorizationController.class})
public class AuthAdvice {
    private final IdentityService identities;
    public AuthAdvice(IdentityService identities) { this.identities = identities; }
    @ExceptionHandler(IdentityException.class)
    public ResponseEntity<AuthResponses.Error> identity(IdentityException failure, HttpServletRequest request) {
        if (failure.status() == 400 || request.getServletPath().startsWith("/internal/") || request.getServletPath().startsWith("/api/m2/admin/")) {
            try {
                Principal actor = (Principal) request.getAttribute(AuthFilter.PRINCIPAL);
                identities.recordRejection("auth:request-denied", actor, failure.code(), AuthFilter.trace(request));
            } catch (IdentityException unavailable) { failure = unavailable; }
        }
        var builder = ResponseEntity.status(failure.status());
        if (failure.retryAfterSeconds() != null) { builder.header("Retry-After", failure.retryAfterSeconds().toString()); }
        return builder.body(AuthResponses.error(failure, AuthFilter.trace(request)));
    }
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<AuthResponses.Error> invalid(HttpServletRequest request) {
        return identity(new IdentityException(400, "VALIDATION_ERROR", "请求字段或 JSON 格式不合法", "body", null, null), request);
    }
    @ExceptionHandler({DataAccessException.class, TransactionException.class})
    public ResponseEntity<AuthResponses.Error> unavailable(HttpServletRequest request) {
        return identity(new IdentityException(503, "AUTH_UNAVAILABLE", "身份服务暂不可用，请稍后重试"), request);
    }
    @ExceptionHandler(DuplicateKeyException.class)
    public ResponseEntity<AuthResponses.Error> duplicate(HttpServletRequest request) {
        return identity(new IdentityException(409,"DUPLICATE_CODE","编号或名称已经存在"),request);
    }
}
