package cn.edu.campus.approval;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.crypto.password.Pbkdf2PasswordEncoder;
import tools.jackson.databind.json.JsonMapper;
import org.springframework.boot.SpringApplication;
import cn.edu.campus.approval.g5.api.IdentityPort;
import cn.edu.campus.approval.g5.api.IdentityException;

import static org.junit.jupiter.api.Assertions.*;

/** Acceptance tests use an isolated real PostgreSQL database and the packaged HTTP application. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AuthFlowIT {
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final JsonMapper json = new JsonMapper();
    private final PasswordEncoder encoder = new DelegatingPasswordEncoder("pbkdf2@SpringSecurity_v5_8",
            Map.of("pbkdf2@SpringSecurity_v5_8", Pbkdf2PasswordEncoder.defaultsForSpringSecurity_v5_8()));
    private final String initial = "initial-" + UUID.randomUUID().toString().substring(0, 16);
    private final String internalToken = "service-" + UUID.randomUUID();
    private final List<String> secrets = new ArrayList<>();
    private String changed;
    private Process process;
    private int appPort;
    private int managementPort;
    private int starts;

    private record Session(String token, String csrf, Map<String, Object> principal) { }
    private record User(String code, String password, UUID id) { }

    @BeforeAll
    void startApplication() throws Exception {
        // This suite changes fixtures and passwords; refuse the normal development database.
        String url = System.getenv("DB_URL");
        assertTrue(url != null && url.matches("jdbc:postgresql://[^/]+/pj_g5_auth_it_[a-zA-Z0-9_]+(?:\\?.*)?"),
                "AuthFlowIT requires a dedicated pj_g5_auth_it_* database, never pj_local/production");
        secrets.add(initial);
        start();
    }
    @BeforeEach
    void clearRateFixtures() throws Exception { sql("DELETE FROM m2.g5_auth_throttle"); }
    @AfterAll
    void stopApplication() throws Exception { stop(); }

    @Test @Order(1)
    void bootstrapAndCompletePasswordLifecycle() throws Exception {
        assertEquals(6L, number("SELECT count(*) FROM m2.role WHERE built_in"));
        assertEquals(1L, number("SELECT count(*) FROM m2.account"));
        String stored = text("SELECT password_hash FROM m2.account WHERE user_code='it-admin'");
        assertTrue(stored.startsWith("{pbkdf2@SpringSecurity_v5_8}"));
        assertNotEquals(initial, stored);
        assertTrue(encoder.matches(initial, stored));
        assertEquals(1L, number("""
                SELECT count(*) FROM m2.g5_audit_event WHERE event->'audit'->>'action'='account:bootstrap'
                AND event->'aggregate'->>'type'='ACCOUNT' AND event->>'actorKind'='SYSTEM'
                AND event->'aggregate'->>'id'=(SELECT id::text FROM m2.account WHERE user_code='it-admin')
                """));

        Session first = login("it-admin", initial);
        Session second = login("it-admin", initial);
        assertEquals(Boolean.FALSE, first.principal().get("mustChangePassword"));
        List<Map<String, Object>> initialGrants = cast(first.principal().get("grants"));
        assertTrue(initialGrants.stream().anyMatch(grant -> grant.get("action").equals("system:manage")));
        assertInstanceOf(String.class, first.principal().get("accountVersion"));
        assertEquals(200, request("GET", "/api/m2/auth/me", null, first, null).statusCode());
        assertEquals(200, request("GET", "/api/m2/admin/accounts", null, first, null).statusCode());
        assertCode(change(first, initial, "short", "1"), 400, "VALIDATION_ERROR");
        assertCode(change(first, initial, initial, "1"), 400, "VALIDATION_ERROR");
        assertCode(change(first, "wrong-current", "replacement-password", "1"), 403, "CURRENT_PASSWORD_INVALID");
        assertCode(change(first, initial, "replacement-password", "2"), 409, "VERSION_CONFLICT");
        assertCode(change(first, initial, "replacement-password", 1), 400, "VALIDATION_ERROR");
        assertCode(request("POST", "/api/m2/auth/change-password", passwordBody(initial, "replacement-password", "1"),
                new Session(first.token(), null, first.principal()), null), 403, "FORBIDDEN");

        // More than 72 UTF-8 bytes, including supplementary Unicode code points: no bcrypt-style truncation.
        changed = "😀".repeat(24);
        secrets.add(changed);
        HttpResponse<String> success = change(first, initial, changed, "1");
        assertEquals(200, success.statusCode());
        assertEquals(Boolean.TRUE, JsonPath.read(success.body(), "$.data.reloginRequired"));
        assertTrue(success.headers().firstValue("Set-Cookie").orElseThrow().contains("Max-Age=0"));
        assertCode(request("GET", "/api/m2/auth/me", null, first, null), 401, "UNAUTHENTICATED");
        assertCode(request("GET", "/api/m2/auth/me", null, second, null), 401, "UNAUTHENTICATED");
        assertCode(loginResponse("it-admin", initial), 401, "UNAUTHENTICATED");
        assertCode(loginResponse("it-admin", "😀".repeat(23) + "😁"), 401, "UNAUTHENTICATED");
        Session unrestricted = login("it-admin", changed);
        assertEquals(Boolean.FALSE, unrestricted.principal().get("mustChangePassword"));
        List<Map<String, Object>> grants = cast(unrestricted.principal().get("grants"));
        assertTrue(grants.stream().anyMatch(grant -> grant.get("action").equals("system:manage")));
        assertFalse(grants.stream().anyMatch(grant -> grant.get("action").equals("approval:level1")));
        assertCode(request("POST", "/api/m2/auth/logout", Map.of(),
                new Session(unrestricted.token(), "incorrect", unrestricted.principal()), null), 403, "FORBIDDEN");
        assertEquals(200, request("POST", "/api/m2/auth/logout", Map.of(), unrestricted, null).statusCode());
        assertCode(request("GET", "/api/m2/auth/me", null, unrestricted, null), 401, "UNAUTHENTICATED");
        assertEquals(200, request("POST", "/api/m2/auth/logout", Map.of(), unrestricted, null).statusCode());
        assertEquals(200, request("POST", "/api/m2/auth/logout", Map.of(), null, null).statusCode());
    }

    @Test @Order(2)
    void restartPreservesPasswordSessionsAndEditedSeeds() throws Exception {
        Session session = login("it-admin", changed);
        String hash = text("SELECT password_hash FROM m2.account WHERE user_code='it-admin'");
        long bindings = number("SELECT count(*) FROM m2.role_binding");
        sql("UPDATE m2.role SET name='学生（已编辑）' WHERE key='STUDENT'");
        sql("DELETE FROM m2.role_grant WHERE action='workorder:evaluate' AND role_id=(SELECT id FROM m2.role WHERE key='STUDENT')");
        stop();
        start();
        assertEquals(hash, text("SELECT password_hash FROM m2.account WHERE user_code='it-admin'"));
        assertEquals(bindings, number("SELECT count(*) FROM m2.role_binding"));
        assertEquals("学生（已编辑）", text("SELECT name FROM m2.role WHERE key='STUDENT'"));
        assertEquals(0L, number("SELECT count(*) FROM m2.role_grant WHERE action='workorder:evaluate' AND role_id=(SELECT id FROM m2.role WHERE key='STUDENT')"));
        assertEquals(200, request("GET", "/api/m2/auth/me", null, session, null).statusCode());
        assertEquals(Boolean.FALSE, login("it-admin", changed).principal().get("mustChangePassword"));
        assertCode(loginResponse("it-admin", initial), 401, "UNAUTHENTICATED");
    }

    @Test @Order(3)
    void accountExpiryAndVersionRevocationUseCurrentDatabaseFacts() throws Exception {
        User disabled = user(false);
        HttpResponse<String> disabledResult = loginResponse(disabled.code(), disabled.password());
        HttpResponse<String> unknownResult = loginResponse("nonexistent", disabled.password());
        assertCode(disabledResult, 401, "UNAUTHENTICATED");
        assertCode(unknownResult, 401, "UNAUTHENTICATED");
        assertEquals(JsonPath.<String>read(disabledResult.body(), "$.message"), JsonPath.<String>read(unknownResult.body(), "$.message"));

        User revoked = user(true);
        Session session = login(revoked.code(), revoked.password());
        sql("UPDATE m2.account SET enabled=false WHERE id=?", revoked.id());
        assertCode(request("GET", "/api/m2/auth/me", null, session, null), 401, "UNAUTHENTICATED");
        User expired = user(true);
        Session expiredSession = login(expired.code(), expired.password());
        sql("UPDATE m2.session SET expires_at=created_at+interval '1 millisecond' WHERE account_id=?", expired.id());
        assertCode(request("GET", "/api/m2/auth/me", null, expiredSession, null), 401, "UNAUTHENTICATED");
        User permissions = user(true);
        Session permissionsSession = login(permissions.code(), permissions.password());
        sql("UPDATE m2.account SET permission_version=permission_version+1 WHERE id=?", permissions.id());
        assertCode(request("GET", "/api/m2/auth/me", null, permissionsSession, null), 401, "UNAUTHENTICATED");
        User version = user(true);
        Session versionSession = login(version.code(), version.password());
        sql("UPDATE m2.account SET version=version+1 WHERE id=?", version.id());
        assertCode(request("GET", "/api/m2/auth/me", null, versionSession, null), 401, "UNAUTHENTICATED");
    }

    @Test @Order(4)
    void persistentAccountAndIpRateLimitsCannotBeBypassedByRestartOrForwardedHeader() throws Exception {
        User account = user(true);
        for (int i = 0; i < 10; i++) { assertCode(loginResponse(account.code(), "incorrect"), 401, "UNAUTHENTICATED"); }
        HttpResponse<String> limited = loginResponse(account.code(), account.password());
        assertCode(limited, 429, "RATE_LIMITED");
        assertTrue(Integer.parseInt(limited.headers().firstValue("Retry-After").orElseThrow()) > 0);
        assertCode(loginResponse("different-account", "incorrect"), 429, "RATE_LIMITED");
        assertCode(request("POST", "/api/m2/auth/login", Map.of("account", account.code(), "password", account.password()),
                null, Map.of("X-Forwarded-For", "203.0.113.42")), 429, "RATE_LIMITED");
        stop();
        start();
        assertCode(loginResponse(account.code(), account.password()), 429, "RATE_LIMITED");
        sql("UPDATE m2.g5_auth_throttle SET blocked_until=CURRENT_TIMESTAMP-interval '1 second'");
        Session session = login(account.code(), account.password());
        for (int i = 0; i < 10; i++) {
            assertCode(change(session, "incorrect", "replacement-password", "1"), 403, "CURRENT_PASSWORD_INVALID");
        }
        assertCode(change(session, account.password(), "replacement-password", "1"), 429, "RATE_LIMITED");
    }

    @Test @Order(5)
    void concurrentPasswordChangesHaveOneWinnerAndRevokeBothSessions() throws Exception {
        User user = user(true);
        Session first = login(user.code(), user.password());
        Session second = login(user.code(), user.password());
        String replacement = "next-" + UUID.randomUUID().toString().substring(0, 16);
        secrets.add(replacement);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var one = executor.submit(() -> change(first, user.password(), replacement, "1"));
            var two = executor.submit(() -> change(second, user.password(), replacement, "1"));
            List<Integer> results = new ArrayList<>(List.of(one.get().statusCode(), two.get().statusCode()));
            results.sort(Integer::compareTo);
            assertEquals(List.of(200, 401), results);
        }
        assertEquals(2L, number("SELECT version FROM m2.account WHERE id=?", user.id()));
        assertEquals(0L, number("SELECT count(*) FROM m2.session WHERE account_id=? AND revoked_at IS NULL", user.id()));
        assertCode(loginResponse(user.code(), user.password()), 401, "UNAUTHENTICATED");
        login(user.code(), replacement);
    }

    @Test @Order(6)
    void internalIdentityAndStrictRequestsKeepCredentialsPrivate() throws Exception {
        User user = user(true);
        Session session = login(user.code(), user.password());
        assertCode(request("POST", "/internal/auth/introspect", Map.of("sessionToken", session.token()), null, null), 403, "FORBIDDEN");
        HttpResponse<String> identity = request("POST", "/internal/auth/introspect", Map.of("sessionToken", session.token()),
                null, Map.of("Authorization", "Bearer " + internalToken));
        assertEquals(200, identity.statusCode());
        assertEquals(user.id().toString(), JsonPath.read(identity.body(), "$.data.userId"));
        assertFalse(identity.body().contains(user.password()));
        assertCode(request("POST", "/internal/auth/introspect", Map.of("sessionToken", "a".repeat(43)), null,
                Map.of("Authorization", "Bearer " + internalToken)), 401, "UNAUTHENTICATED");
        assertCode(request("POST", "/api/m2/auth/login", Map.of("account", user.code(), "password", user.password(), "unknown", true),
                null, null), 400, "VALIDATION_ERROR");
        assertCode(request("POST", "/api/m2/auth/login", Map.of("account", 123, "password", user.password()), null, null), 400, "VALIDATION_ERROR");
        assertCode(request("GET", "/api/m2/auth/me?unknown=true", null, session, null), 400, "VALIDATION_ERROR");
        assertCode(request("GET", "/api/m2/auth/me?unknown=true", null, null, null), 401, "UNAUTHENTICATED");
        assertCode(request("POST", "/api/m2/auth/logout", Map.of("unknown", true), session, null), 400, "VALIDATION_ERROR");
        assertEquals(0L, number("SELECT count(*) FROM m2.session WHERE token_hash=?", session.token()));
        assertEquals(1L, number("SELECT count(*) FROM m2.session WHERE length(token_hash)=64 AND account_id=?", user.id()));
        String events = text("SELECT COALESCE(string_agg(event::text,''),'') FROM m2.g5_audit_event");
        for (String secret : secrets) { assertFalse(events.contains(secret), "Audit must not contain any test password"); }
        assertFalse(events.contains(session.token()));
        assertFalse(events.contains(session.csrf()));
        assertFalse(events.contains("pbkdf2@SpringSecurity"));
        assertEquals(number("SELECT count(*) FROM m2.g5_audit_event"), number("SELECT count(*) FROM m2.g5_outbox"));
        assertTrue(number("SELECT count(*) FROM m2.g5_audit_event WHERE event->'audit'->>'result'='FAILURE'") > 0);
        User samePassword = user(true, user.password());
        assertNotEquals(text("SELECT password_hash FROM m2.account WHERE id=?", user.id()),
                text("SELECT password_hash FROM m2.account WHERE id=?", samePassword.id()), "Identical passwords need random salts");
    }

    private void start() throws Exception {
        appPort = freePort();
        do { managementPort = freePort(); } while (managementPort == appPort);
        Path jar = Path.of(System.getProperty("appJar")).toAbsolutePath();
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        ProcessBuilder builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", executable).toString(), "-jar", jar.toString());
        builder.environment().putAll(Map.of("APP_BIND_ADDRESS", "127.0.0.1", "APP_PORT", Integer.toString(appPort),
                "MANAGEMENT_PORT", Integer.toString(managementPort), "G5_BOOTSTRAP_ENABLED", "true", "G5_BOOTSTRAP_ACCOUNT", "it-admin",
                "G5_BOOTSTRAP_PASSWORD", initial, "G5_SERVICE_G1_TOKEN", internalToken));
        builder.redirectErrorStream(true).redirectOutput(jar.getParent().resolve("auth-it-start-" + (++starts) + ".log").toFile());
        process = builder.start();
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (System.nanoTime() < deadline) {
            assertTrue(process.isAlive(), "Application exited; inspect local auth-it-start log");
            try {
                var response = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + managementPort + "/actuator/health/readiness"))
                        .timeout(Duration.ofSeconds(3)).GET().build(), HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) { return; }
            } catch (IOException ignored) { }
            Thread.sleep(200);
        }
        fail("Authentication application failed to become ready");
    }

    @Test @Order(7)
    void localIdentityPortUsesCurrentSessionAndAccountFacts() throws Exception {
        User user = user(true);
        Session session = login(user.code(), user.password());
        SpringApplication application = new SpringApplication(Application.class);
        application.addInitializers(new RequiredDatabaseConfiguration());
        try (var context = application.run("--server.port=0", "--management.server.port=0", "--G5_BOOTSTRAP_ENABLED=false")) {
            IdentityPort port = context.getBean(IdentityPort.class);
            assertEquals(user.id(), port.introspect(session.token()).userId());
            sql("UPDATE m2.account SET enabled=false WHERE id=?", user.id());
            IdentityException refusal = assertThrows(IdentityException.class, () -> port.introspect(session.token()));
            assertEquals(401, refusal.status());
        }
    }

    @Test @Order(8)
    void duplicateServiceCredentialsPreventStartup() throws Exception {
        Path jar = Path.of(System.getProperty("appJar")).toAbsolutePath();
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        ProcessBuilder builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", executable).toString(), "-jar", jar.toString());
        builder.environment().putAll(Map.of("APP_PORT", Integer.toString(freePort()), "MANAGEMENT_PORT", Integer.toString(freePort()),
                "G5_SERVICE_G1_TOKEN", internalToken, "G5_SERVICE_G3_TOKEN", internalToken));
        builder.redirectErrorStream(true).redirectOutput(jar.getParent().resolve("auth-it-duplicate-services.log").toFile());
        Process duplicate = builder.start();
        try {
            assertTrue(duplicate.waitFor(45, TimeUnit.SECONDS));
            assertNotEquals(0, duplicate.exitValue());
        } finally {
            if (duplicate.isAlive()) { duplicate.destroyForcibly(); duplicate.waitFor(10, TimeUnit.SECONDS); }
        }
    }
    @Test @Order(9)
    void passwordBoundsUseCodePointsAndExistingShortCredentialsRemainUsable() throws Exception {
        User legacy = user(true, "legacy");
        Session session = login(legacy.code(), legacy.password());
        assertEquals(Boolean.FALSE, session.principal().get("mustChangePassword"));
        assertCode(change(session, legacy.password(), "a".repeat(7), "1"), 400, "VALIDATION_ERROR");
        assertCode(change(session, legacy.password(), "a".repeat(25), "1"), 400, "VALIDATION_ERROR");
        assertCode(change(session, legacy.password(), "😀".repeat(25), "1"), 400, "VALIDATION_ERROR");
        assertCode(change(session, legacy.password(), "valid\npassword", "1"), 400, "VALIDATION_ERROR");
        String minimum = UUID.randomUUID().toString().substring(0, 8);
        secrets.add(minimum);
        assertEquals(200, change(session, legacy.password(), minimum, "1").statusCode());
        Session minimumSession = login(legacy.code(), minimum);
        String maximum = "😀".repeat(24);
        secrets.add(maximum);
        assertEquals(200, change(minimumSession, minimum, maximum, "2").statusCode());
        Session maximumSession = login(legacy.code(), maximum);
        assertEquals(Boolean.FALSE, maximumSession.principal().get("mustChangePassword"));
        assertCode(request("GET", "/api/m2/auth/me", null, minimumSession, null), 401, "UNAUTHENTICATED");
    }
    private void stop() throws Exception {
        if (process != null) {
            process.destroy();
            if (!process.waitFor(15, TimeUnit.SECONDS)) { process.destroyForcibly(); }
            assertTrue(process.waitFor(10, TimeUnit.SECONDS));
            assertFalse(process.isAlive());
        }
    }
    private int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) { return socket.getLocalPort(); }
    }
    private HttpResponse<String> loginResponse(String code, String password) throws Exception {
        return request("POST", "/api/m2/auth/login", Map.of("account", code, "password", password), null, null);
    }
    private Session login(String code, String password) throws Exception {
        HttpResponse<String> response = loginResponse(code, password);
        assertEquals(200, response.statusCode(), "Login must succeed for a valid fixture");
        String cookie = response.headers().firstValue("Set-Cookie").orElseThrow();
        for (String flag : List.of("Secure", "HttpOnly", "SameSite=Lax", "Max-Age=28800", "Path=/")) { assertTrue(cookie.contains(flag)); }
        String token = cookie.substring("PJ_SESSION=".length(), cookie.indexOf(';'));
        return new Session(token, JsonPath.read(response.body(), "$.data.csrfToken"), JsonPath.read(response.body(), "$.data.principal"));
    }
    private Map<String, Object> passwordBody(String old, String replacement, Object version) {
        return Map.of("currentPassword", old, "newPassword", replacement, "expectedVersion", version);
    }
    private HttpResponse<String> change(Session session, String old, String replacement, Object version) throws Exception {
        return request("POST", "/api/m2/auth/change-password", passwordBody(old, replacement, version), session, null);
    }
    private HttpResponse<String> request(String method, String path, Object body, Session session, Map<String, String> headers) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + appPort + path)).timeout(Duration.ofSeconds(15));
        if (body != null) { builder.header("Content-Type", "application/json"); }
        if (session != null) {
            builder.header("Cookie", "PJ_SESSION=" + session.token());
            if (session.csrf() != null) { builder.header("X-CSRF-Token", session.csrf()); }
        }
        if (headers != null) { headers.forEach(builder::header); }
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
    private void assertCode(HttpResponse<String> response, int status, String code) {
        assertEquals(status, response.statusCode(), "Expected " + code);
        assertEquals(code, JsonPath.read(response.body(), "$.code"));
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
        UUID.fromString(JsonPath.read(response.body(), "$.traceId"));
        assertNotNull(JsonPath.read(response.body(), "$.details.fields"));
    }
    private User user(boolean enabled) throws Exception { return user(enabled, "fixture-" + UUID.randomUUID().toString().substring(0, 16)); }
    private User user(boolean enabled, String password) throws Exception {
        secrets.add(password);
        UUID id = UUID.randomUUID();
        String code = "it-" + id;
        sql("""
                INSERT INTO m2.account(id,user_code,display_name,department_id,email,password_hash,enabled,must_change_password)
                VALUES (?,?,'测试账户',(SELECT id FROM m2.department WHERE code='SYSTEM'),'fixture@example.invalid',?,?,false)
                """, id, code, encoder.encode(password), enabled);
        return new User(code, password, id);
    }
    private Connection database() throws Exception {
        return DriverManager.getConnection(System.getenv("DB_URL"), System.getenv("DB_MIGRATION_USERNAME"), System.getenv("DB_MIGRATION_PASSWORD"));
    }
    private void sql(String query, Object... parameters) throws Exception {
        try (Connection connection = database(); PreparedStatement statement = connection.prepareStatement(query)) {
            for (int i = 0; i < parameters.length; i++) { statement.setObject(i + 1, parameters[i]); }
            statement.executeUpdate();
        }
    }
    private Object scalar(String query, Object... parameters) throws Exception {
        try (Connection connection = database(); PreparedStatement statement = connection.prepareStatement(query)) {
            for (int i = 0; i < parameters.length; i++) { statement.setObject(i + 1, parameters[i]); }
            try (ResultSet result = statement.executeQuery()) { assertTrue(result.next()); return result.getObject(1); }
        }
    }
    private long number(String query, Object... parameters) throws Exception { return ((Number) scalar(query, parameters)).longValue(); }
    private String text(String query, Object... parameters) throws Exception { return (String) scalar(query, parameters); }
    @SuppressWarnings("unchecked") private static <T> T cast(Object value) { return (T) value; }
}
