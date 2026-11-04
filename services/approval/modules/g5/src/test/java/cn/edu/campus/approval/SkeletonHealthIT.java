package cn.edu.campus.approval;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import com.jayway.jsonpath.JsonPath;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Runs the packaged application against real PostgreSQL, without stopping the database. */
class SkeletonHealthIT {
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2)).build();

    @Test
    void packagedServiceDetectsDatabaseFailureRecoversAndRestarts() throws Exception {
        Map<String, String> environment = databaseEnvironment();
        URI database = URI.create(environment.get("DB_URL").substring("jdbc:".length()));
        assertEquals("postgresql", database.getScheme(), "Integration test requires PostgreSQL");
        try (DatabaseProxy proxy = new DatabaseProxy(database.getHost(),
                database.getPort() < 0 ? 5432 : database.getPort())) {
            String query = database.getRawQuery();
            String proxyUrl = "jdbc:postgresql://127.0.0.1:" + proxy.port() + database.getRawPath()
                    + "?" + (query == null ? "" : query + "&") + "connectTimeout=2&socketTimeout=3";
            environment.put("DB_URL", proxyUrl);
            environment.put("DB_MIGRATION_URL", proxyUrl);

            try (RunningApp app = start(environment, "database-lifecycle")) {
                awaitHealth(app, "/actuator/health/readiness", 200, "UP");
                assertHealth(app, "/actuator/health", 200, "UP");
                assertHealth(app, "/actuator/health/liveness", 200, "UP");
                assertEquals(404, get(app.appPort(), "/actuator/health").statusCode());
                assertEquals(404, get(app.managementPort(), "/actuator/env").statusCode());
                assertEquals(404, get(app.managementPort(), "/actuator/beans").statusCode());
                assertEquals(404, get(app.managementPort(), "/actuator/mappings").statusCode());

                proxy.setAvailable(false);
                awaitHealth(app, "/actuator/health/readiness", 503, "DOWN");
                assertHealth(app, "/actuator/health", 503, "DOWN");
                assertHealth(app, "/actuator/health/liveness", 200, "UP");
                HttpResponse<String> unavailable = http.send(HttpRequest.newBuilder(URI.create(
                        "http://127.0.0.1:" + app.appPort() + "/api/m2/auth/me"))
                        .header("Cookie", "PJ_SESSION=" + "a".repeat(43)).timeout(Duration.ofSeconds(12))
                        .GET().build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(503, unavailable.statusCode());
                assertEquals("AUTH_UNAVAILABLE", JsonPath.read(unavailable.body(), "$.code"));

                proxy.setAvailable(true);
                awaitHealth(app, "/actuator/health/readiness", 200, "UP");
                assertHealth(app, "/actuator/health", 200, "UP");
            }

            try (RunningApp restarted = start(environment, "restart")) {
                awaitHealth(restarted, "/actuator/health/readiness", 200, "UP");
                assertHealth(restarted, "/actuator/health", 200, "UP");
            }
        }
    }

    @Test
    void incorrectMigrationPasswordPreventsStartup() throws Exception {
        Map<String, String> environment = databaseEnvironment();
        environment.put("DB_MIGRATION_PASSWORD", "invalid-" + UUID.randomUUID());
        try (RunningApp app = start(environment, "wrong-migration-password")) {
            assertTrue(app.process().waitFor(45, TimeUnit.SECONDS),
                    "Invalid database credentials must prevent startup; see " + app.log());
            assertNotEquals(0, app.process().exitValue(), "Invalid credentials must fail startup");
        }
    }

    @Test
    void missingRuntimePasswordPreventsStartup() throws Exception {
        Map<String, String> environment = databaseEnvironment();
        environment.remove("DB_PASSWORD");
        try (RunningApp app = start(environment, "missing-runtime-password")) {
            assertTrue(app.process().waitFor(45, TimeUnit.SECONDS),
                    "Missing runtime credentials must prevent startup; see " + app.log());
            assertNotEquals(0, app.process().exitValue(), "Missing credentials must fail startup");
        }
    }

    private Map<String, String> databaseEnvironment() {
        Map<String, String> result = new HashMap<>();
        for (String name : new String[]{"DB_URL", "DB_USERNAME", "DB_PASSWORD",
                "DB_MIGRATION_USERNAME", "DB_MIGRATION_PASSWORD"}) {
            String value = System.getenv(name);
            assertTrue(value != null && !value.isBlank(),
                    "Set " + name + " for the real PostgreSQL integration test");
            result.put(name, value);
        }
        String migrationUrl = System.getenv("DB_MIGRATION_URL");
        if (migrationUrl != null && !migrationUrl.isBlank()) {
            result.put("DB_MIGRATION_URL", migrationUrl);
        }
        return result;
    }

    private RunningApp start(Map<String, String> environment, String scenario) throws IOException {
        Path jar = Path.of(System.getProperty("appJar")).toAbsolutePath();
        assertTrue(Files.isRegularFile(jar), "Package the executable application before integration tests");
        Path log = jar.getParent().resolve("skeleton-it-" + scenario + ".log");
        String javaName = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        Path java = Path.of(System.getProperty("java.home"), "bin", javaName);
        ProcessBuilder builder = new ProcessBuilder(java.toString(), "-jar", jar.toString());
        // Prevent a deliberately absent variable being restored by ProcessBuilder's inherited environment.
        for (String name : new String[]{"DB_URL", "DB_USERNAME", "DB_PASSWORD",
                "DB_MIGRATION_URL", "DB_MIGRATION_USERNAME", "DB_MIGRATION_PASSWORD"}) {
            builder.environment().remove(name);
        }
        builder.environment().putAll(environment);
        int appPort = freePort();
        int managementPort;
        do {
            managementPort = freePort();
        } while (managementPort == appPort);
        builder.environment().put("APP_BIND_ADDRESS", "127.0.0.1");
        builder.environment().put("APP_PORT", Integer.toString(appPort));
        builder.environment().put("MANAGEMENT_PORT", Integer.toString(managementPort));
        builder.redirectErrorStream(true).redirectOutput(log.toFile());
        return new RunningApp(builder.start(), appPort, managementPort, log);
    }

    private int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            return socket.getLocalPort();
        }
    }

    private HttpResponse<String> get(int port, String path) throws IOException, InterruptedException {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(6)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private void assertHealth(RunningApp app, String path, int status, String health) throws Exception {
        HttpResponse<String> response = get(app.managementPort(), path);
        assertEquals(status, response.statusCode(), path);
        assertHealthBody(response.body(), path, health);
    }

    private void assertHealthBody(String json, String path, String health) {
        Map<String, Object> body = JsonPath.parse(json).read("$");
        assertEquals(health, body.get("status"));
        if (path.equals("/actuator/health")) {
            assertEquals(Set.of("status", "groups"), body.keySet(),
                    "Aggregate health may expose probe names, but no component or credential details");
            assertEquals(Set.of("liveness", "readiness"), Set.copyOf((List<?>) body.get("groups")));
        } else {
            assertEquals(Set.of("status"), body.keySet(), "Probe must not expose connection details");
        }
    }

    private void awaitHealth(RunningApp app, String path, int status, String health) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        String last = "No HTTP response";
        while (System.nanoTime() < deadline) {
            assertTrue(app.process().isAlive(), "Application exited; see " + app.log());
            try {
                HttpResponse<String> response = get(app.managementPort(), path);
                last = "HTTP " + response.statusCode() + " " + response.body();
                if (response.statusCode() == status
                        && health.equals(JsonPath.read(response.body(), "$.status"))) {
                    assertHealthBody(response.body(), path, health);
                    return;
                }
            } catch (IOException ignored) {
                last = "Connection unavailable";
            }
            Thread.sleep(200);
        }
        throw new AssertionError(path + " did not become " + health + "; " + last + "; see " + app.log());
    }

    private record RunningApp(Process process, int appPort, int managementPort, Path log)
            implements AutoCloseable {
        @Override
        public void close() throws InterruptedException {
            process.destroy();
            if (!process.waitFor(15, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                assertTrue(process.waitFor(10, TimeUnit.SECONDS), "Test application did not stop");
            }
            assertFalse(process.isAlive(), "Test application must not remain running");
        }
    }

    /** Only this application's connections are interrupted; PostgreSQL remains running. */
    private static final class DatabaseProxy implements AutoCloseable {
        private final String host;
        private final int upstreamPort;
        private final ServerSocket listener;
        private final ExecutorService workers = Executors.newCachedThreadPool(task -> {
            Thread thread = new Thread(task, "postgres-it-proxy");
            thread.setDaemon(true);
            return thread;
        });
        private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
        private volatile boolean available = true;

        private DatabaseProxy(String host, int upstreamPort) throws IOException {
            this.host = host;
            this.upstreamPort = upstreamPort;
            this.listener = new ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"));
            workers.execute(() -> {
                while (!listener.isClosed()) {
                    try {
                        Socket client = listener.accept();
                        workers.execute(() -> relay(client));
                    } catch (IOException ignored) {
                        break;
                    }
                }
            });
        }

        private int port() {
            return listener.getLocalPort();
        }

        private void relay(Socket client) {
            try (client; Socket upstream = new Socket()) {
                sockets.add(client);
                sockets.add(upstream);
                if (!available) {
                    return;
                }
                upstream.connect(new InetSocketAddress(host, upstreamPort), 2000);
                if (!available) {
                    return;
                }
                workers.execute(() -> copy(client, upstream));
                copy(upstream, client);
            } catch (IOException ignored) {
                // A broken upstream is the deliberate failure scenario, not a simulated health result.
            } finally {
                sockets.remove(client);
                sockets.removeIf(Socket::isClosed);
            }
        }

        private void copy(Socket source, Socket target) {
            try {
                source.getInputStream().transferTo(target.getOutputStream());
            } catch (IOException ignored) {
                // Closing either side ends both forwarding directions.
            } finally {
                closeSocket(source);
                closeSocket(target);
            }
        }

        private void setAvailable(boolean value) {
            available = value;
            if (!value) {
                sockets.forEach(DatabaseProxy::closeSocket);
            }
        }

        private static void closeSocket(Socket socket) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // Cleanup is idempotent.
            }
        }

        @Override
        public void close() throws IOException, InterruptedException {
            available = false;
            listener.close();
            sockets.forEach(DatabaseProxy::closeSocket);
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(10, TimeUnit.SECONDS), "Proxy workers did not stop");
        }
    }
}
