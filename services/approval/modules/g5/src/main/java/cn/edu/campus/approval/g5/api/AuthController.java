package cn.edu.campus.approval.g5.api;

import java.time.Duration;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import cn.edu.campus.approval.g5.application.IdentityService;
import cn.edu.campus.approval.g5.domain.Credentials;

@org.springframework.context.annotation.Profile("!shell")
@RestController
public class AuthController {
    private final IdentityService identities;
    public AuthController(IdentityService identities) { this.identities = identities; }
    public record LoginBody(Object account, Object password) {
        @Override public String toString() { return "LoginBody[credentials=REDACTED]"; }
    }
    public record PasswordBody(Object currentPassword, Object newPassword, Object expectedVersion) {
        @Override public String toString() { return "PasswordBody[credentials=REDACTED]"; }
    }
    /** Map semantics preserve missing-vs-null validation without logging bearer/CSRF values. */
    public static final class IntrospectionBody extends java.util.LinkedHashMap<String,Object> {
        private static final long serialVersionUID = 1L;
        @Override public String toString() { return "IntrospectionBody[credentials=REDACTED]"; }
    }
    @PostMapping("/api/m2/auth/login")
    public AuthResponses.Ok<?> login(@RequestBody LoginBody body, HttpServletRequest request, HttpServletResponse response) {
        var login = identities.login(string(body.account(), "account"), string(body.password(), "password"), request.getRemoteAddr(), AuthFilter.trace(request));
        cookie(response, login.sessionToken(), Duration.ofHours(8));
        return new AuthResponses.Ok<>(Map.of("principal", login.principal(), "csrfToken", login.csrfToken()), AuthFilter.trace(request));
    }
    @GetMapping("/api/m2/auth/me")
    public AuthResponses.Ok<Principal> me(HttpServletRequest request) {
        return new AuthResponses.Ok<>((Principal) request.getAttribute(AuthFilter.PRINCIPAL), AuthFilter.trace(request));
    }
    @PostMapping("/api/m2/auth/logout")
    public AuthResponses.Ok<?> logout(@RequestBody Map<String, Object> body, HttpServletRequest request, HttpServletResponse response) {
        if (!body.isEmpty()) { throw Credentials.invalid("body", "请求体必须为空对象"); }
        identities.logout(AuthFilter.token(request), request.getHeader("X-CSRF-Token"), AuthFilter.trace(request));
        cookie(response, "", Duration.ZERO);
        return new AuthResponses.Ok<>(Map.of("revoked", true), AuthFilter.trace(request));
    }
    @PostMapping("/api/m2/auth/change-password")
    public AuthResponses.Ok<?> changePassword(@RequestBody PasswordBody body, HttpServletRequest request, HttpServletResponse response) {
        identities.changePassword(AuthFilter.token(request), request.getHeader("X-CSRF-Token"), string(body.currentPassword(), "currentPassword"),
                string(body.newPassword(), "newPassword"), string(body.expectedVersion(), "expectedVersion"), request.getRemoteAddr(), AuthFilter.trace(request));
        cookie(response, "", Duration.ZERO);
        return new AuthResponses.Ok<>(Map.of("passwordChanged", true, "reloginRequired", true), AuthFilter.trace(request));
    }
    @PostMapping("/internal/auth/introspect")
    public AuthResponses.Ok<Principal> introspect(@RequestBody IntrospectionBody body, HttpServletRequest request) {
        for (String field : body.keySet()) {
            if (!java.util.Set.of("sessionToken", "requestMethod", "csrfToken").contains(field)) {
                throw Credentials.invalid(field, "此接口不接受该字段");
            }
        }
        String token = Credentials.text(string(body.get("sessionToken"), "sessionToken"), 32, 256, "sessionToken", false);
        String method = body.containsKey("requestMethod") ? string(body.get("requestMethod"), "requestMethod") : "GET";
        if (!java.util.Set.of("GET", "HEAD", "OPTIONS", "POST", "PUT", "PATCH", "DELETE").contains(method)) {
            throw Credentials.invalid("requestMethod", "须为受支持的大写 HTTP 方法");
        }
        String csrf = body.containsKey("csrfToken") ? string(body.get("csrfToken"), "csrfToken") : null;
        return new AuthResponses.Ok<>(identities.introspectRequest(token, method, csrf), AuthFilter.trace(request));
    }
    private static void cookie(HttpServletResponse response, String value, Duration age) {
        response.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from(AuthFilter.COOKIE, value)
                .httpOnly(true).secure(true).sameSite("Lax").path("/").maxAge(age).build().toString());
    }
    private static String string(Object value, String field) {
        if (!(value instanceof String text)) { throw Credentials.invalid(field, "此字段必须为字符串"); }
        return text;
    }
}
