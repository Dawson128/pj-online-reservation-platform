package cn.edu.campus.security;

import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

class G5IntrospectionClientTest {
    private HttpServer server;
    private final AtomicInteger calls=new AtomicInteger();
    private final AtomicReference<String> request=new AtomicReference<>();
    private final AtomicReference<String> bearer=new AtomicReference<>();
    private final JsonMapper json=new JsonMapper();
    private static final String SESSION="s".repeat(43), SERVICE="service-"+"a".repeat(40), CSRF="c".repeat(43);
    private static final String ID="00000000-0000-4000-8000-000000000001";
    private int status=200;
    private String response=good();
    private long delay;
    static String good() { return """
        {"data":{"userId":"00000000-0000-4000-8000-000000000001","displayName":"测试用户","deptId":"00000000-0000-4000-8000-000000000002","accountVersion":"1","permissionVersion":"1","grants":[{"action":"room:read","scope":{"kind":"ROOM","roomId":"00000000-0000-4000-8000-000000000003"}}],"mustChangePassword":false,"expiresAt":"2030-01-01T08:00:00+08:00"},"traceId":"00000000-0000-4000-8000-000000000004"}
        """; }
    @BeforeEach void start() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/internal/auth/introspect",exchange->{
            calls.incrementAndGet();request.set(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
            bearer.set(exchange.getRequestHeaders().getFirst("Authorization"));
            if(delay>0)try{Thread.sleep(delay);}catch(InterruptedException failure){Thread.currentThread().interrupt();}
            byte[] bytes=response.getBytes(StandardCharsets.UTF_8);
            if(status==302)exchange.getResponseHeaders().set("Location","/unexpected");
            exchange.sendResponseHeaders(status,bytes.length);
            try{exchange.getResponseBody().write(bytes);}catch(java.io.IOException ignored){}finally{exchange.close();}
        });
        server.createContext("/unexpected",exchange->{calls.incrementAndGet();exchange.sendResponseHeaders(200,-1);exchange.close();});
        server.start();
    }
    @AfterEach void stop(){server.stop(0);}
    G5IntrospectionClient client(){return new G5IntrospectionClient(URI.create("http://127.0.0.1:"+server.getAddress().getPort()),false,module->SERVICE);}
    AuthFailure failure(Runnable action,int expected,String code){AuthFailure failure=assertThrows(AuthFailure.class,action::run);assertEquals(expected,failure.status());assertEquals(code,failure.code());return failure;}
    @Test void sendsMethodAndOriginalCsrfWithoutCachingOrLeakingCredentials() throws Exception {
        CampusPrincipal principal=client().authenticate("G4",SESSION,"POST",CSRF);
        assertEquals(ID,principal.getName());assertEquals("Bearer "+SERVICE,bearer.get());
        var body=json.readTree(request.get());assertEquals(SESSION,body.path("sessionToken").asText());
        assertEquals("POST",body.path("requestMethod").asText());assertEquals(CSRF,body.path("csrfToken").asText());
        client().authenticate("G4",SESSION,"GET",null);assertEquals(2,calls.get());
        assertFalse(request.get().contains("csrfToken"));assertFalse(principal.toString().contains(SESSION));
        assertThrows(UnsupportedOperationException.class,()->principal.grants().clear());
        assertThrows(UnsupportedOperationException.class,()->principal.grants().getFirst().scope().put("kind","SCHOOL"));
    }
    @Test void sendsUnsafeMethodEvenWhenCsrfIsMissingSoG5DecidesAfterAuthentication() throws Exception {
        client().authenticate("G3",SESSION,"DELETE",null);
        var body=json.readTree(request.get());assertEquals("DELETE",body.path("requestMethod").asText());assertFalse(body.has("csrfToken"));
    }
    @Test void mapsRevokedSessionTo401(){status=401;response="{\"code\":\"UNAUTHENTICATED\"}";failure(()->client().authenticate("G4",SESSION,"GET",null),401,"UNAUTHENTICATED");}
    @Test void mapsOnlyStructuredCsrfRejectionTo403(){status=403;response="{\"code\":\"FORBIDDEN\",\"details\":{\"fields\":[{\"field\":\"csrfToken\",\"reason\":\"invalid\"}]}}";failure(()->client().authenticate("G4",SESSION,"POST",CSRF),403,"FORBIDDEN");}
    @Test void invalidServiceCredentialsAreDependencyFailure(){status=403;response="{\"code\":\"FORBIDDEN\",\"details\":{\"fields\":[]}}";failure(()->client().authenticate("G4",SESSION,"GET",null),503,"AUTH_UNAVAILABLE");}
    @Test void malformedProviderResponseIsNotAnAuthenticatedUser(){response="<html>bad gateway</html>";failure(()->client().authenticate("G4",SESSION,"GET",null),503,"AUTH_UNAVAILABLE");}
    @Test void oversizedProviderResponseIsRejected(){response="x".repeat(524289);failure(()->client().authenticate("G4",SESSION,"GET",null),503,"AUTH_UNAVAILABLE");}
    @Test void numericVersionsAreRejected(){response=good().replace("\"accountVersion\":\"1\"","\"accountVersion\":1");failure(()->client().authenticate("G4",SESSION,"GET",null),503,"AUTH_UNAVAILABLE");}
    @Test void extraScopeFieldsAreRejected(){response=good().replace("\"kind\":\"ROOM\"","\"kind\":\"ROOM\",\"admin\":true");failure(()->client().authenticate("G4",SESSION,"GET",null),503,"AUTH_UNAVAILABLE");}
    @Test void redirectsDoNotForwardTheSessionToAnotherEndpoint(){status=302;failure(()->client().authenticate("G4",SESSION,"GET",null),503,"AUTH_UNAVAILABLE");assertEquals(1,calls.get());}
    @Test void timeoutFailsClosedAndDoesNotRetry(){delay=400;var client=new G5IntrospectionClient(URI.create("http://127.0.0.1:"+server.getAddress().getPort()),false,module->SERVICE,Duration.ofMillis(100));failure(()->client.authenticate("G4",SESSION,"GET",null),503,"AUTH_UNAVAILABLE");assertTrue(calls.get()<=1,"Timeout may occur before dispatch on a busy runner, but must never cause a retry");}
    @Test void absentSessionDoesNotContactG5(){failure(()->client().authenticate("G4",null,"GET",null),401,"UNAUTHENTICATED");assertEquals(0,calls.get());}
    @Test void missingServiceCredentialDoesNotContactG5(){var client=new G5IntrospectionClient(URI.create("http://127.0.0.1:"+server.getAddress().getPort()),false,module->null);failure(()->client.authenticate("G4",SESSION,"GET",null),503,"AUTH_UNAVAILABLE");assertEquals(0,calls.get());}
    @Test void upstreamErrorsNeverEchoSessionOrCsrf(){status=500;response=SESSION+CSRF+SERVICE;var error=failure(()->client().authenticate("G4",SESSION,"POST",CSRF),503,"AUTH_UNAVAILABLE");assertFalse(error.toString().contains(SESSION));assertFalse(error.toString().contains(SERVICE));}
    @Test void restrictedPrincipalCannotAccessBusiness(){response=good().replace("\"mustChangePassword\":false","\"mustChangePassword\":true");failure(()->client().authenticate("G4",SESSION,"GET",null),403,"PASSWORD_CHANGE_REQUIRED");}
    @Test void httpOutsideLoopbackRequiresExplicitDevelopmentOptIn(){assertThrows(IllegalArgumentException.class,()->new G5IntrospectionClient(URI.create("http://approval:8082"),false,module->SERVICE));assertDoesNotThrow(()->new G5IntrospectionClient(URI.create("http://approval:8082"),true,module->SERVICE));}
    @Test void foreignModuleRulesAreRejected(){assertThrows(IllegalArgumentException.class,()->new ApiAccessRule("G4","GET","/api/m3/repairs","room:read"));}
}
