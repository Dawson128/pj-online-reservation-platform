package cn.edu.campus.approval;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.*;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.*;
import org.springframework.boot.SpringApplication;
import tools.jackson.databind.json.JsonMapper;
import cn.edu.campus.approval.g5.api.ConfigurationPort;
import cn.edu.campus.approval.g5.api.IdentityException;
import static org.junit.jupiter.api.Assertions.*;

/** Provider acceptance with real HTTP/PostgreSQL; no G1/G2/G6 business consumer is simulated as integrated. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ConfigurationFlowIT {
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final JsonMapper json=new JsonMapper();
    private final String password=secret(),hmac=key()+key();
    private final Map<String,String> serviceTokens=new LinkedHashMap<>();
    private final Map<String,Integer> defaults=Map.of("reservationMaxHours",4,"reservationAdvanceDays",7,"applicationAdvanceDays",14,"scanIntervalSeconds",10,"notificationRetryLimit",5);
    private String database;
    private Process process;
    private int port,managementPort;
    private Session admin,student;
    private record Session(String token,String csrf) { }
    @BeforeAll void start() throws Exception {
        database=System.getenv("G5_CONFIG_TEST_DB_URL");
        assertTrue(database!=null&&database.matches("jdbc:postgresql://[^/]+/pj_g5_auth_it_config_[a-zA-Z0-9_]+"),"Use a dedicated configuration test database");
        assertNotEquals(database,System.getenv("DB_URL"));assertNotEquals(database,System.getenv("G5_MANAGEMENT_TEST_DB_URL"));
        for(String group:List.of("G1","G2","G3","G4","G6")) serviceTokens.put(group,key()+key());
        startBackend();admin=login("config-manager",password);
        String department=JsonPath.read(request("POST","/api/m2/admin/departments",Map.of("code","CONFIG","name","配置验证院系"),admin,writeHeaders()).body(),"$.data.id");
        String role=JsonPath.read(request("GET","/api/m2/admin/roles?keyword=STUDENT",null,admin,null).body(),"$.data.items[0].id");
        String studentPassword=secret();
        assertEquals(201,request("POST","/api/m2/admin/accounts",Map.of("userCode","config-student","displayName","配置验证学生","departmentId",department,"email","config@example.invalid","initialPassword",studentPassword,"roleIds",List.of(role)),admin,writeHeaders()).statusCode());
        student=login("config-student",studentPassword);
    }
    @AfterAll void stop() throws Exception { stopBackend(); }

    @Test @Order(1) void defaultsPersistAndManagementAndServiceAuthenticationStaySeparate() throws Exception {
        var current=get();assertEquals("1",text(current,"version"));assertEquals(defaults,values(current));
        assertEquals(1,count("SELECT count(*) FROM m2.g5_config_history"));
        assertCode(request("GET","/api/m2/admin/config",null,student,null),403,"FORBIDDEN");
        assertCode(request("PATCH","/api/m2/admin/config",Map.of("invalid",true),student,writeHeaders()),403,"FORBIDDEN");
        assertCode(request("GET","/api/m2/admin/config",null,null,null),401,"UNAUTHENTICATED");
        assertCode(request("PATCH","/api/m2/admin/config",body(current,defaults),new Session(admin.token(),null),writeHeaders()),403,"FORBIDDEN");
        assertCode(request("PATCH","/api/m2/admin/config",body(current,defaults),admin,null),400,"VALIDATION_ERROR");
        assertCode(request("GET","/internal/config",null,admin,null),403,"FORBIDDEN");
        assertCode(request("GET","/internal/config",null,null,Map.of("Authorization","Bearer "+key())),403,"FORBIDDEN");
        for(String group:serviceTokens.keySet()) {
            var internal=request("GET","/internal/config",null,null,Map.of("Authorization","Bearer "+serviceTokens.get(group)));
            assertEquals(200,internal.statusCode());assertEquals(json.readTree(current.body()).path("data"),json.readTree(internal.body()).path("data"));assertEquals("no-store",internal.headers().firstValue("Cache-Control").orElseThrow());
        }
        assertCode(request("POST","/internal/auth/introspect",Map.of("sessionToken",admin.token()),null,Map.of("Authorization","Bearer "+serviceTokens.get("G2"))),403,"FORBIDDEN");
        assertCode(request("GET","/api/m2/admin/config?unknown=1",null,admin,null),400,"VALIDATION_ERROR");
        assertCode(request("GET","/internal/config?unknown=1",null,null,Map.of("Authorization","Bearer "+serviceTokens.get("G1"))),400,"VALIDATION_ERROR");
    }
    @Test @Order(2) void everyDocumentedBoundaryIsValidatedAndFullReplacementIsRequired() throws Exception {
        Map<String,int[]> bounds=Map.of("reservationMaxHours",new int[]{1,8},"reservationAdvanceDays",new int[]{1,7},"applicationAdvanceDays",new int[]{1,14},"scanIntervalSeconds",new int[]{1,30},"notificationRetryLimit",new int[]{0,5});
        for(var entry:bounds.entrySet()) {
            for(int allowed:entry.getValue()) {
                var before=get();Map<String,Object> values=new TreeMap<>(values(before));values.put(entry.getKey(),allowed);
                var response=patch(before,values,key());assertEquals(200,response.statusCode());assertEquals(allowed,values(response).get(entry.getKey()));
            }
            for(Object forbidden:Arrays.asList(entry.getValue()[0]-1,entry.getValue()[1]+1,1.5,true,"2",null)) {
                var before=get();Map<String,Object> values=new TreeMap<>(values(before));values.put(entry.getKey(),forbidden);
                assertCode(patch(before,values,key()),400,"VALIDATION_ERROR");assertEquals(text(before,"version"),text(get(),"version"));
            }
        }
        var before=get();Map<String,Object> missing=new TreeMap<>(values(before));missing.remove("scanIntervalSeconds");assertCode(patch(before,missing,key()),400,"VALIDATION_ERROR");
        Map<String,Object> extra=new TreeMap<>(values(before));extra.put("earlyMinutes",99);assertCode(patch(before,extra,key()),400,"VALIDATION_ERROR");
        assertCode(request("PATCH","/api/m2/admin/config",Map.of("expectedVersion",1,"values",defaults),admin,writeHeaders()),400,"VALIDATION_ERROR");
        assertCode(request("PATCH","/api/m2/admin/config",Map.of("expectedVersion",text(before,"version"),"values",defaults,"extra",true),admin,writeHeaders()),400,"VALIDATION_ERROR");
        Map<String,Object> integral=new TreeMap<>(values(before));integral.put("reservationMaxHours",2.0);assertEquals(200,patch(before,integral,key()).statusCode());
    }
    @Test @Order(3) void concurrentSavesAcceptOnlyOneVersionAndKeepBothCompleteSnapshots() throws Exception {
        var before=get();String version=text(before,"version");Map<String,Object> first=new TreeMap<>(values(before)),second=new TreeMap<>(values(before));first.put("reservationMaxHours",3);second.put("reservationMaxHours",4);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var a=pool.submit(()->patch(before,first,key()));var b=pool.submit(()->patch(before,second,key()));var ra=a.get();var rb=b.get();
            List<Integer> statuses=new ArrayList<>(List.of(ra.statusCode(),rb.statusCode()));statuses.sort(Integer::compareTo);assertEquals(List.of(200,409),statuses);
            var refused=ra.statusCode()==409?ra:rb;assertCode(refused,409,"VERSION_CONFLICT");
            assertEquals(Long.parseLong(version)+1,Long.parseLong(text(get(),"version")));
            assertEquals(Long.parseLong(text(get(),"version")),count("SELECT count(*) FROM m2.g5_config_history"));
        }
        assertEquals("4",scalar("SELECT values->>'reservationMaxHours' FROM m2.g5_config_history WHERE version=1"));
    }
    @Test @Order(4) void replaySurvivesRestartAndNoOpDoesNotChangeVersionOrEmitEmptyEvent() throws Exception {
        var before=get();Map<String,Object> changed=new TreeMap<>(values(before));changed.put("reservationMaxHours",values(before).get("reservationMaxHours")==5?6:5);
        String originalKey=key();var committed=patch(before,changed,originalKey);assertEquals(200,committed.statusCode());
        var replay=patch(before,changed,originalKey);assertEquals("true",replay.headers().firstValue("Idempotency-Replayed").orElseThrow());assertEquals(json.readTree(committed.body()),json.readTree(replay.body()));
        Map<String,Object> laterValues=new TreeMap<>(values(committed));laterValues.put("scanIntervalSeconds",values(committed).get("scanIntervalSeconds")==11?12:11);var later=patch(committed,laterValues,key());assertEquals(200,later.statusCode());
        stopBackend();startBackend();admin=login("config-manager",password);
        assertEquals(text(later,"version"),text(get(),"version"));var oldReplay=patch(before,changed,originalKey);assertEquals(json.readTree(committed.body()),json.readTree(oldReplay.body()));assertEquals(values(later),values(get()));
        assertCode(patch(before,laterValues,originalKey),409,"IDEMPOTENCY_CONFLICT");
        long events=count("SELECT count(*) FROM m2.g5_audit_event WHERE event->>'eventType'='ConfigChanged'");var now=get();String noOpKey=key();var noOp=patch(now,new TreeMap<>(values(now)),noOpKey);assertEquals(200,noOp.statusCode());assertEquals(json.readTree(now.body()).path("data"),json.readTree(noOp.body()).path("data"));
        assertEquals(events,count("SELECT count(*) FROM m2.g5_audit_event WHERE event->>'eventType'='ConfigChanged'"));assertEquals(1,count("SELECT count(*) FROM m2.g5_idempotency WHERE request_key='"+noOpKey+"'"));
        assertEquals(200,request("GET","/api/m2/auth/me",null,student,null).statusCode());
    }
    @Test @Order(5) void outboxFailureRollsBackConfigHistoryAndIdempotencyAndMissingStoreNeverDefaults() throws Exception {
        var before=get();long history=count("SELECT count(*) FROM m2.g5_config_history");String requestKey=key();Map<String,Object> changed=new TreeMap<>(values(before));changed.put("reservationMaxHours",7);
        sql("CREATE FUNCTION m2.fail_config_outbox() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'isolated config outbox failure'; END; $$");sql("CREATE TRIGGER fail_config_outbox BEFORE INSERT ON m2.g5_outbox FOR EACH ROW EXECUTE FUNCTION m2.fail_config_outbox()");
        try { assertCode(patch(before,changed,requestKey),503,"AUTH_UNAVAILABLE"); }
        finally { sql("DROP TRIGGER fail_config_outbox ON m2.g5_outbox");sql("DROP FUNCTION m2.fail_config_outbox()"); }
        assertEquals(values(before),values(get()));assertEquals(text(before,"version"),text(get(),"version"));assertEquals(history,count("SELECT count(*) FROM m2.g5_config_history"));assertEquals(0,count("SELECT count(*) FROM m2.g5_idempotency WHERE request_key='"+requestKey+"'"));
        sql("ALTER TABLE m2.business_config RENAME TO business_config_temporarily_unavailable");
        try { assertCode(request("GET","/internal/config",null,null,Map.of("Authorization","Bearer "+serviceTokens.get("G1"))),503,"AUTH_UNAVAILABLE"); }
        finally { sql("ALTER TABLE m2.business_config_temporarily_unavailable RENAME TO business_config"); }
        assertEquals(values(before),values(get()));
    }
    @Test @Order(6) void eventsContainChangedKeysVersionsActorsAndFullBeforeAfterValues() throws Exception {
        String event=scalar("SELECT event::text FROM m2.g5_audit_event WHERE event->>'eventType'='ConfigChanged' ORDER BY created_at,event_id LIMIT 1");
        assertEquals("CONFIG",JsonPath.read(event,"$.aggregate.type"));assertEquals(JsonPath.<String>read(event,"$.aggregateVersion"),JsonPath.read(event,"$.payload.configVersion"));assertNotNull(JsonPath.read(event,"$.actorId"));
        List<?> changed=JsonPath.read(event,"$.payload.changedKeys");assertFalse(changed.isEmpty());assertTrue(changed.size()<=5);
        assertEquals(7,JsonPath.<List<?>>read(event,"$.audit.before").size());assertEquals(7,JsonPath.<List<?>>read(event,"$.audit.after").size());assertFalse(event.contains(password));assertFalse(event.contains(hmac));
        assertEquals(count("SELECT count(*) FROM m2.g5_audit_event"),count("SELECT count(*) FROM m2.g5_outbox"));
    }
    @Test @Order(7) void localConfigurationPortReadsPersistedCurrentSnapshotAndFailsClosed() throws Exception {
        var expected=get();stopBackend();var app=new SpringApplication(Application.class);app.addInitializers(new RequiredDatabaseConfiguration());
        try(var context=app.run("--server.port=0","--management.server.port=0","--G5_BOOTSTRAP_ENABLED=false","--spring.datasource.url="+database,"--spring.flyway.url="+database)) {
            var port=context.getBean(ConfigurationPort.class);var snapshot=port.current();assertEquals(text(expected,"version"),snapshot.version());assertEquals(values(expected),snapshot.values());
            assertThrows(UnsupportedOperationException.class,()->snapshot.values().put("reservationMaxHours",99));
            sql("ALTER TABLE m2.business_config RENAME TO business_config_temporarily_unavailable");
            try { assertEquals(503,assertThrows(IdentityException.class,port::current).status()); }
            finally { sql("ALTER TABLE m2.business_config_temporarily_unavailable RENAME TO business_config"); }
            assertEquals(snapshot.values(),port.current().values());
        }
    }
    private static String key(){return UUID.randomUUID().toString();}
    private static String secret(){return UUID.randomUUID().toString().replace("-","").substring(0,24);}
    private Map<String,String> writeHeaders(){return Map.of("Idempotency-Key",key());}
    private String text(HttpResponse<String> r,String field){return JsonPath.read(r.body(),"$.data."+field);}
    private Map<String,Integer> values(HttpResponse<String> r){return JsonPath.read(r.body(),"$.data.values");}
    private Map<String,Object> body(HttpResponse<String> r,Map<String,?> values){return Map.of("expectedVersion",text(r,"version"),"values",values);}
    private HttpResponse<String> get() throws Exception {var r=request("GET","/api/m2/admin/config",null,admin,null);assertEquals(200,r.statusCode());return r;}
    private HttpResponse<String> patch(HttpResponse<String> before,Map<String,?> values,String key) throws Exception {return request("PATCH","/api/m2/admin/config",body(before,values),admin,Map.of("Idempotency-Key",key));}
    private Session login(String code,String password) throws Exception {var r=request("POST","/api/m2/auth/login",Map.of("account",code,"password",password),null,null);assertEquals(200,r.statusCode());String cookie=r.headers().firstValue("Set-Cookie").orElseThrow();return new Session(cookie.substring(11,cookie.indexOf(';')),JsonPath.read(r.body(),"$.data.csrfToken"));}
    private HttpResponse<String> request(String method,String path,Object body,Session session,Map<String,String> headers) throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(15));
        if(session!=null){request.header("Cookie","PJ_SESSION="+session.token());if(session.csrf()!=null)request.header("X-CSRF-Token",session.csrf());}if(headers!=null)headers.forEach(request::header);
        if(body!=null)request.header("Content-Type","application/json");request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        return http.send(request.build(),HttpResponse.BodyHandlers.ofString());
    }
    private void assertCode(HttpResponse<String> r,int status,String code){assertEquals(status,r.statusCode(),"Expected "+code);assertEquals(code,JsonPath.read(r.body(),"$.code"));}
    private void startBackend() throws Exception {
        port=freePort();do{managementPort=freePort();}while(managementPort==port);Path jar=Path.of(System.getProperty("appJar")).toAbsolutePath();
        var command=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString(),"-jar",jar.toString());
        command.environment().putAll(Map.of("DB_URL",database,"DB_MIGRATION_URL",database,"APP_BIND_ADDRESS","127.0.0.1","APP_PORT",Integer.toString(port),"MANAGEMENT_PORT",Integer.toString(managementPort),"G5_BOOTSTRAP_ENABLED","true","G5_BOOTSTRAP_ACCOUNT","config-manager","G5_BOOTSTRAP_PASSWORD",password,"G5_IDEMPOTENCY_HMAC_KEY",hmac));
        serviceTokens.forEach((group,token)->command.environment().put("G5_SERVICE_"+group+"_TOKEN",token));command.redirectErrorStream(true).redirectOutput(jar.getParent().resolve("config-it-"+port+".log").toFile());process=command.start();
        long deadline=System.nanoTime()+Duration.ofSeconds(60).toNanos();while(System.nanoTime()<deadline){assertTrue(process.isAlive(),"Configuration backend exited; inspect ignored log");try{if(http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+managementPort+"/actuator/health/readiness")).GET().build(),HttpResponse.BodyHandlers.ofString()).statusCode()==200)return;}catch(IOException ignored){}Thread.sleep(200);}fail("Config backend readiness timeout");
    }
    private void stopBackend() throws Exception {if(process!=null&&process.isAlive()){process.destroy();if(!process.waitFor(15,TimeUnit.SECONDS)){process.destroyForcibly();assertTrue(process.waitFor(10,TimeUnit.SECONDS));}}}
    private int freePort() throws IOException {try(var socket=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))){return socket.getLocalPort();}}
    private void sql(String query) throws Exception {try(var connection=DriverManager.getConnection(database,System.getenv("DB_MIGRATION_USERNAME"),System.getenv("DB_MIGRATION_PASSWORD"));var statement=connection.createStatement()){statement.execute(query);}}
    private String scalar(String query) throws Exception {try(var connection=DriverManager.getConnection(database,System.getenv("DB_MIGRATION_USERNAME"),System.getenv("DB_MIGRATION_PASSWORD"));var statement=connection.createStatement();var rows=statement.executeQuery(query)){assertTrue(rows.next());return rows.getString(1);}}
    private long count(String query) throws Exception {return Long.parseLong(scalar(query));}
}
