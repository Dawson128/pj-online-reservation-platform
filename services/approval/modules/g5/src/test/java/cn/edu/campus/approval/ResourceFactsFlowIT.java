package cn.edu.campus.approval;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.*;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.*;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

/** Real G5 provider HTTP/PostgreSQL acceptance. G4 business effects are not simulated as integrated. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ResourceFactsFlowIT {
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final JsonMapper json=new JsonMapper();
    private final String password=secret(),hmac=key()+key();
    private final Map<String,String> serviceTokens=new LinkedHashMap<>();
    private String database,departmentA,departmentB,userA,userB,adminId;
    private Process process;
    private int port,managementPort;
    private Session admin,student;
    private record Session(String token,String csrf) { }
    @BeforeAll void start() throws Exception {
        database=System.getenv("G5_RESOURCE_FACTS_TEST_DB_URL");
        assertTrue(database!=null&&database.matches("jdbc:postgresql://[^/]+/pj_g5_auth_it_resource_facts_[a-zA-Z0-9_]+"),"Use a dedicated resource-facts database");
        for(String variable:List.of("DB_URL","G5_MANAGEMENT_TEST_DB_URL","G5_CONFIG_TEST_DB_URL")) assertNotEquals(database,System.getenv(variable));
        for(String group:List.of("G1","G2","G3","G4","G6")) serviceTokens.put(group,key()+key());
        startBackend();admin=login("facts-manager",password);adminId=text(request("GET","/api/m2/auth/me",null,admin,null),"userId");
        departmentA=createDepartment("FACT_A");departmentB=createDepartment("FACT_B");
        String role=JsonPath.read(request("GET","/api/m2/admin/roles?keyword=STUDENT",null,admin,null).body(),"$.data.items[0].id");
        String studentPassword=secret();userA=createUser("facts-a",studentPassword,departmentA,role);userB=createUser("facts-b",secret(),departmentB,role);
        student=login("facts-a",studentPassword);
        assertEquals(200,request("PATCH","/api/m2/admin/accounts/"+userB,Map.of("expectedVersion","1","enabled",false),admin,writeHeaders()).statusCode());
    }
    @AfterAll void stop() throws Exception {stopBackend();}

    @Test @Order(1) void onlyG4MayReadFactsAndAuthenticationPrecedesPayloadValidation() throws Exception {
        String departmentPath="/internal/departments/"+departmentA;Map<String,Object> valid=factBody(List.of(userA));
        assertCode(request("GET",departmentPath,null,null,null),403,"FORBIDDEN");
        assertCode(request("POST","/internal/accounts/qualification-facts",Map.of("bad",true),admin,null),403,"FORBIDDEN");
        assertCode(request("GET",departmentPath,null,student,null),403,"FORBIDDEN");
        for(String group:List.of("G1","G2","G3","G6")) {
            assertCode(request("GET",departmentPath,null,null,service(group)),403,"FORBIDDEN");
            assertCode(request("POST","/internal/accounts/qualification-facts",Map.of("bad",true),null,service(group)),403,"FORBIDDEN");
        }
        assertCode(request("POST","/internal/accounts/qualification-facts",valid,admin,Map.of("Authorization","Bearer "+key(),"X-Service-Group","G4")),403,"FORBIDDEN");
        var duplicate=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+departmentPath)).header("Authorization","Bearer "+serviceTokens.get("G4")).header("Authorization","Bearer "+key()).GET().build();
        assertCode(http.send(duplicate,HttpResponse.BodyHandlers.ofString()),403,"FORBIDDEN");
        assertEquals(200,request("GET",departmentPath,null,null,service("G4")).statusCode());
        assertEquals(200,request("POST","/internal/accounts/qualification-facts",valid,null,service("G4")).statusCode());
    }
    @Test @Order(2) void encodedControllerPathsCannotBypassAuthenticationOrCallerRestriction() throws Exception {
        assertCode(request("GET","/%69nternal/departments/"+departmentA,null,null,null),403,"FORBIDDEN");
        assertCode(request("GET","/internal/%64epartments/"+departmentA,null,null,service("G1")),403,"FORBIDDEN");
        assertCode(request("POST","/%69nternal/accounts/qualification-facts",factBody(List.of(userA)),null,service("G3")),403,"FORBIDDEN");
        assertEquals(200,request("GET","/internal/%64epartments/"+departmentA,null,null,service("G4")).statusCode());
        assertCode(request("GET","/%61pi/m2/auth/me",null,null,null),401,"UNAUTHENTICATED");
        assertCode(request("GET","/api/m2/%61dmin/accounts",null,student,null),403,"FORBIDDEN");
    }
    @Test @Order(3) void departmentReturnsCurrentDisabledRecordAndOnlyDepartmentFields() throws Exception {
        var result=department(departmentA);assertEquals(200,result.statusCode());
        Map<String,Object> record=JsonPath.read(result.body(),"$.data");assertEquals(Set.of("id","version","createdAt","updatedAt","code","name","enabled"),record.keySet());
        assertEquals("no-store",result.headers().firstValue("Cache-Control").orElseThrow());
        assertEquals(200,request("PATCH","/api/m2/admin/departments/"+departmentA,Map.of("expectedVersion","1","enabled",false),admin,writeHeaders()).statusCode());
        var disabled=department(departmentA);assertEquals(200,disabled.statusCode());assertFalse(JsonPath.<Boolean>read(disabled.body(),"$.data.enabled"));assertEquals("2",text(disabled,"version"));
        assertEquals(200,request("PATCH","/api/m2/admin/departments/"+departmentA,Map.of("expectedVersion","2","name","停用历史院系"),admin,writeHeaders()).statusCode());assertEquals("停用历史院系",text(department(departmentA),"name"));
        assertCode(department(key()),404,"NOT_FOUND");assertCode(department("not-a-uuid"),400,"VALIDATION_ERROR");
        assertCode(request("GET","/internal/departments/"+departmentA+"?unknown=1",null,null,service("G4")),400,"VALIDATION_ERROR");
        assertCode(request("GET","/internal/departments/"+departmentA,Map.of("extra",true),null,service("G4")),400,"VALIDATION_ERROR");
    }
    @Test @Order(4) void qualificationIsMinimalOrderedIncludesDisabledAndHasFiveSecondDbClockValidity() throws Exception {
        var body=factBody(List.of(userB,userA,adminId,userA));Instant before=Instant.now();var response=facts(body);Instant after=Instant.now();assertEquals(200,response.statusCode());
        assertEquals(body.get("traceId"),JsonPath.read(response.body(),"$.traceId"));assertEquals(body.get("traceId"),response.headers().firstValue("X-Trace-Id").orElseThrow());
        assertEquals("no-store",response.headers().firstValue("Cache-Control").orElseThrow());
        Map<String,Object> result=JsonPath.read(response.body(),"$.data");assertEquals(Set.of("items","checkedAt","validUntil"),result.keySet());
        var items=items(response);assertEquals(List.of(userB,userA,adminId,userA),items.stream().map(item->item.get("userId")).toList());
        for(var item:items) assertEquals(Set.of("userId","departmentId","enabled","accountVersion"),item.keySet());
        assertEquals(false,items.getFirst().get("enabled"));assertEquals("2",items.getFirst().get("accountVersion"));assertEquals(departmentA,items.get(1).get("departmentId"));assertEquals(items.get(1),items.get(3));
        OffsetDateTime checked=OffsetDateTime.parse((String)result.get("checkedAt")),until=OffsetDateTime.parse((String)result.get("validUntil"));
        assertEquals(Duration.ofSeconds(5),Duration.between(checked,until));assertEquals(ZoneOffset.ofHours(8),checked.getOffset());assertFalse(checked.toInstant().isBefore(before.minusSeconds(1)));assertFalse(checked.toInstant().isAfter(after.plusSeconds(1)));
        for(String forbidden:List.of("email","password","roleIds","grants","userCode","displayName",password,hmac)) assertFalse(response.body().contains(forbidden));
    }
    @Test @Order(5) void malformedPurposeIdsFieldsAndBatchBoundsAreRejectedWithoutPartialResults() throws Exception {
        Map<String,Object> valid=factBody(List.of(userA));
        for(String field:valid.keySet()) {Map<String,Object> missing=new LinkedHashMap<>(valid);missing.remove(field);assertCode(facts(missing),400,"VALIDATION_ERROR");}
        for(String purpose:List.of("FINAL_APPROVAL","REPAIR_START","notification","")) {Map<String,Object> invalid=new LinkedHashMap<>(valid);invalid.put("purpose",purpose);assertCode(facts(invalid),400,"VALIDATION_ERROR");}
        for(String field:List.of("roomId","traceId")) for(Object invalid:Arrays.asList("bad",123,null,"AAAAAAAA-AAAA-AAAA-AAAA-AAAAAAAAAAAA")) {Map<String,Object> body=new LinkedHashMap<>(valid);body.put(field,invalid);assertCode(facts(body),400,"VALIDATION_ERROR");}
        for(Object invalid:Arrays.asList(List.of(),Collections.nCopies(101,userA),List.of(123),List.of("bad"),"not-an-array",null)) {Map<String,Object> body=new LinkedHashMap<>(valid);body.put("userIds",invalid);assertCode(facts(body),400,"VALIDATION_ERROR");}
        Map<String,Object> extra=new LinkedHashMap<>(valid);extra.put("actorUserId",adminId);assertCode(facts(extra),400,"VALIDATION_ERROR");
        var missing=facts(factBody(List.of(userA,key(),userB)));assertCode(missing,404,"NOT_FOUND");assertFalse(json.readTree(missing.body()).has("data"));assertFalse(missing.body().contains("departmentId"));
        assertCode(request("POST","/internal/accounts/qualification-facts?unknown=1",valid,null,service("G4")),400,"VALIDATION_ERROR");
    }
    @Test @Order(6) void oneHundredDistinctRowsAreCompleteAndCurrentChangesDoNotNeedUserSession() throws Exception {
        // Fixture only: the read API is under test; authentication/creation business logic is covered separately.
        sql("INSERT INTO m2.account(id,user_code,display_name,department_id,email,password_hash) SELECT gen_random_uuid(),'facts-bulk-'||n::text,'合成资格夹具', '"+departmentB+"'::uuid,'fixture@example.invalid',a.password_hash FROM generate_series(1,100) n CROSS JOIN m2.account a WHERE a.user_code='facts-manager'");
        List<String> ids=Arrays.asList(scalar("SELECT string_agg(id::text,',' ORDER BY user_code) FROM m2.account WHERE user_code LIKE 'facts-bulk-%'").split(","));assertEquals(100,ids.size());
        var response=facts(factBody(ids));assertEquals(200,response.statusCode());assertEquals(ids,items(response).stream().map(item->(String)item.get("userId")).toList());
        assertEquals(200,request("PATCH","/api/m2/admin/accounts/"+userA,Map.of("expectedVersion","1","departmentId",departmentB,"enabled",false),admin,writeHeaders()).statusCode());
        assertCode(request("GET","/api/m2/auth/me",null,student,null),401,"UNAUTHENTICATED");
        var changed=items(facts(factBody(List.of(userA)))).getFirst();assertEquals(departmentB,changed.get("departmentId"));assertEquals(false,changed.get("enabled"));assertEquals("2",changed.get("accountVersion"));
    }
    @Test @Order(7) void concurrentAtomicAccountChangesNeverProduceMixedBatchFacts() throws Exception {
        sql("UPDATE m2.account SET enabled=true,version=version+1 WHERE id IN ('"+userA+"','"+userB+"')");
        AtomicBoolean running=new AtomicBoolean(true);AtomicInteger writes=new AtomicInteger();CountDownLatch started=new CountDownLatch(1);
        try(var pool=Executors.newSingleThreadExecutor()) {
            var writer=pool.submit(()->{
                try(var connection=DriverManager.getConnection(database,System.getenv("DB_MIGRATION_USERNAME"),System.getenv("DB_MIGRATION_PASSWORD"));var statement=connection.createStatement()) {
                    started.countDown();while(running.get()) {statement.executeUpdate("UPDATE m2.account SET enabled=NOT enabled,version=version+1 WHERE id IN ('"+userA+"','"+userB+"')");writes.incrementAndGet();Thread.sleep(2);}
                } return null;
            });
            assertTrue(started.await(5,TimeUnit.SECONDS));
            try {for(int i=0;i<30;i++){var response=facts(factBody(List.of(userB,userA)));assertEquals(200,response.statusCode());var items=items(response);assertEquals(items.get(0).get("enabled"),items.get(1).get("enabled"));}}
            finally {running.set(false);writer.get(10,TimeUnit.SECONDS);}
        }
        assertTrue(writes.get()>0);
    }
    @Test @Order(8) void dependencyFailureIsNotEmptyOrCachedAndProviderRestartsWithoutChangingFacts() throws Exception {
        var before=items(facts(factBody(List.of(userA,userB))));
        sql("ALTER TABLE m2.account RENAME TO account_temporarily_unavailable");
        try {var unavailable=facts(factBody(List.of(userA,userB)));assertCode(unavailable,503,"DEPENDENCY_UNAVAILABLE");assertFalse(json.readTree(unavailable.body()).has("data"));}
        finally {sql("ALTER TABLE m2.account_temporarily_unavailable RENAME TO account");}
        sql("ALTER TABLE m2.department RENAME TO department_temporarily_unavailable");
        try {assertCode(department(departmentA),503,"DEPENDENCY_UNAVAILABLE");}
        finally {sql("ALTER TABLE m2.department_temporarily_unavailable RENAME TO department");}
        assertEquals(before,items(facts(factBody(List.of(userA,userB)))));
        stopBackend();startBackend();admin=login("facts-manager",password);
        assertEquals(before,items(facts(factBody(List.of(userA,userB)))));assertEquals("停用历史院系",text(department(departmentA),"name"));
        String events=scalar("SELECT coalesce(string_agg(event::text,''),'') FROM m2.g5_audit_event");assertFalse(events.contains(password));assertFalse(events.contains(hmac));for(String token:serviceTokens.values()) assertFalse(events.contains(token));
    }
    private Map<String,String> service(String group){return Map.of("Authorization","Bearer "+serviceTokens.get(group));}
    private Map<String,Object> factBody(List<String> ids){return Map.of("purpose","RESOURCE_IMPACT","roomId",key(),"userIds",ids,"traceId",key());}
    private HttpResponse<String> facts(Map<String,Object> body) throws Exception {return request("POST","/internal/accounts/qualification-facts",body,null,service("G4"));}
    private HttpResponse<String> department(String id) throws Exception {return request("GET","/internal/departments/"+id,null,null,service("G4"));}
    private List<Map<String,Object>> items(HttpResponse<String> response){assertEquals(200,response.statusCode());return JsonPath.read(response.body(),"$.data.items");}
    private String createDepartment(String code) throws Exception {var r=request("POST","/api/m2/admin/departments",Map.of("code",code,"name",code),admin,writeHeaders());assertEquals(201,r.statusCode());return text(r,"id");}
    private String createUser(String code,String password,String dept,String role) throws Exception {var r=request("POST","/api/m2/admin/accounts",Map.of("userCode",code,"displayName","资格测试账户","departmentId",dept,"email",code+"@example.invalid","initialPassword",password,"roleIds",List.of(role)),admin,writeHeaders());assertEquals(201,r.statusCode());return text(r,"id");}
    private static String key(){return UUID.randomUUID().toString();}
    private static String secret(){return UUID.randomUUID().toString().replace("-","").substring(0,24);}
    private Map<String,String> writeHeaders(){return Map.of("Idempotency-Key",key());}
    private String text(HttpResponse<String> r,String field){return JsonPath.read(r.body(),"$.data."+field);}




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
        command.environment().putAll(Map.of("DB_URL",database,"DB_MIGRATION_URL",database,"APP_BIND_ADDRESS","127.0.0.1","APP_PORT",Integer.toString(port),"MANAGEMENT_PORT",Integer.toString(managementPort),"G5_BOOTSTRAP_ENABLED","true","G5_BOOTSTRAP_ACCOUNT","facts-manager","G5_BOOTSTRAP_PASSWORD",password,"G5_IDEMPOTENCY_HMAC_KEY",hmac));
        serviceTokens.forEach((group,token)->command.environment().put("G5_SERVICE_"+group+"_TOKEN",token));command.redirectErrorStream(true).redirectOutput(jar.getParent().resolve("resource-facts-it-"+port+".log").toFile());process=command.start();
        long deadline=System.nanoTime()+Duration.ofSeconds(60).toNanos();while(System.nanoTime()<deadline){assertTrue(process.isAlive(),"Configuration backend exited; inspect ignored log");try{if(http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+managementPort+"/actuator/health/readiness")).GET().build(),HttpResponse.BodyHandlers.ofString()).statusCode()==200)return;}catch(IOException ignored){}Thread.sleep(200);}fail("Config backend readiness timeout");
    }
    private void stopBackend() throws Exception {if(process!=null&&process.isAlive()){process.destroy();if(!process.waitFor(15,TimeUnit.SECONDS)){process.destroyForcibly();assertTrue(process.waitFor(10,TimeUnit.SECONDS));}}}
    private int freePort() throws IOException {try(var socket=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))){return socket.getLocalPort();}}
    private void sql(String query) throws Exception {try(var connection=DriverManager.getConnection(database,System.getenv("DB_MIGRATION_USERNAME"),System.getenv("DB_MIGRATION_PASSWORD"));var statement=connection.createStatement()){statement.execute(query);}}
    private String scalar(String query) throws Exception {try(var connection=DriverManager.getConnection(database,System.getenv("DB_MIGRATION_USERNAME"),System.getenv("DB_MIGRATION_PASSWORD"));var statement=connection.createStatement();var rows=statement.executeQuery(query)){assertTrue(rows.next());return rows.getString(1);}}
    private long count(String query) throws Exception {return Long.parseLong(scalar(query));}
}
