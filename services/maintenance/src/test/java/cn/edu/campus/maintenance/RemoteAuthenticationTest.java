package cn.edu.campus.maintenance;

import cn.edu.campus.security.*;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.*;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.*;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("shell")
@Import(RemoteAuthenticationTest.ProbeConfiguration.class)
class RemoteAuthenticationTest {
    static final String TOKEN="s".repeat(43);
    static final AtomicInteger calls=new AtomicInteger();
    static final AtomicReference<String> requestBody=new AtomicReference<>();
    static HttpServer server;
    static {
        try {
            server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            server.createContext("/internal/auth/introspect",exchange->{
                calls.incrementAndGet();requestBody.set(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
                byte[] body="""
                    {"data":{"userId":"00000000-0000-4000-8000-000000000001","displayName":"测试用户","deptId":"00000000-0000-4000-8000-000000000002","accountVersion":"1","permissionVersion":"1","grants":[{"action":"system:manage","scope":{"kind":"SYSTEM"}}],"mustChangePassword":false,"expiresAt":"2030-01-01T08:00:00+08:00"},"traceId":"00000000-0000-4000-8000-000000000004"}
                    """.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);exchange.close();
            });server.start();
        }catch(Exception failure){throw new ExceptionInInitializerError(failure);}
    }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("G5_AUTH_BASE_URL",()->"http://127.0.0.1:"+server.getAddress().getPort());
        registry.add("G5_SERVICE_G3_TOKEN",()->"service-"+"g".repeat(40));
    }
    @AfterAll static void stop(){server.stop(0);}
    @Autowired MockMvc mvc;
    @Test void registeredReadUsesRealFilterAndFreshRemoteIdentity() throws Exception {
        int before=calls.get();
        for(int i=0;i<2;i++)mvc.perform(get("/api/m3/__auth_test/read").cookie(new Cookie("PJ_SESSION",TOKEN)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.userId").value("00000000-0000-4000-8000-000000000001"));
        assertEquals(before+2,calls.get());
    }
    @Test void forgedHeadersAreNotIdentity() throws Exception {
        mvc.perform(get("/api/m3/__auth_test/read").header("X-User-Id","administrator").header("Authorization","Bearer "+TOKEN))
            .andExpect(status().isUnauthorized());
    }
    @Test void permissionGateDoesNotGiveSystemAdminEveryBusinessAction() throws Exception {
        mvc.perform(get("/api/m3/__auth_test/denied").cookie(new Cookie("PJ_SESSION",TOKEN)))
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }
    @Test void writeMethodAndCsrfReachProvider() throws Exception {
        mvc.perform(post("/api/m3/__auth_test/write").cookie(new Cookie("PJ_SESSION",TOKEN)).header("X-CSRF-Token","c".repeat(43)))
            .andExpect(status().isOk());
        assertTrue(requestBody.get().contains("\"requestMethod\":\"POST\""));
        assertTrue(requestBody.get().contains("\"csrfToken\":\""+"c".repeat(43)+"\""));
    }
    @Test void duplicateCookieOrCsrfIsRejected() throws Exception {
        mvc.perform(get("/api/m3/__auth_test/read").cookie(new Cookie("PJ_SESSION",TOKEN),new Cookie("PJ_SESSION",TOKEN)))
            .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/m3/__auth_test/write").cookie(new Cookie("PJ_SESSION",TOKEN)).header("X-CSRF-Token","a","b"))
            .andExpect(status().isBadRequest());
    }
    @Test void unregisteredHandlerRemainsClosed() throws Exception {
        mvc.perform(get("/api/m3/__auth_test/unregistered").cookie(new Cookie("PJ_SESSION",TOKEN)))
            .andExpect(status().isUnauthorized());
    }
    @TestConfiguration(proxyBeanMethods=false)
    static class ProbeConfiguration {
        @Bean Probe probe(){return new Probe();}
        @Bean ApiAccessRule readRule(){return new ApiAccessRule("G3","GET","/api/m3/__auth_test/read","system:manage");}
        @Bean ApiAccessRule writeRule(){return new ApiAccessRule("G3","POST","/api/m3/__auth_test/write","system:manage");}
        @Bean ApiAccessRule deniedRule(){return new ApiAccessRule("G3","GET","/api/m3/__auth_test/denied","room:manage");}
    }
    @RestController static class Probe {
        @GetMapping({"/api/m3/__auth_test/read","/api/m3/__auth_test/denied","/api/m3/__auth_test/unregistered"})
        Map<String,Object> read(@AuthenticationPrincipal CampusPrincipal principal){return Map.of("userId",principal.userId().toString());}
        @PostMapping("/api/m3/__auth_test/write") Map<String,Object> write(){return Map.of("written",true);}
    }
}
