package cn.edu.campus.approval;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.*;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

/** All business mutations go through real HTTP. SQL is limited to evidence and failure/expiry fixtures. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ManagementFlowIT {
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final JsonMapper json=new JsonMapper();
    private final String initial=secret(),hmac=UUID.randomUUID().toString()+UUID.randomUUID();
    private final List<String> passwords=new ArrayList<>();
    private String database;
    private Process process;
    private int port,managementPort;
    private Session admin;
    private com.sun.net.httpserver.HttpServer roomServer;
    private final String roomId=key(),roomSecret=key()+key();
    private volatile int roomStatus=200;
    private final java.util.concurrent.atomic.AtomicInteger roomCalls=new java.util.concurrent.atomic.AtomicInteger();
    private String department,studentRole,applicantRole,systemRole;
    private record Session(String token,String csrf) { }
    private record User(String code,String password,String id) { }

    @BeforeAll void start() throws Exception {
        database=System.getenv("G5_MANAGEMENT_TEST_DB_URL");
        assertTrue(database!=null&&database.matches("jdbc:postgresql://[^/]+/pj_g5_auth_it_[a-zA-Z0-9_]+"),"Use scripts/Test-G5Management.ps1 with dedicated databases");
        assertNotEquals(System.getenv("DB_URL"),database,"Management and original authentication fixtures must be isolated");
        passwords.add(initial); startRoomBoundary(); startBackend(); admin=login("management-admin",initial);
        var roleResponse=request("GET","/api/m2/admin/roles?pageSize=100",null,admin,null);
        assertEquals(200,roleResponse.statusCode());
        List<Map<String,Object>> roles=JsonPath.read(roleResponse.body(),"$.data.items");
        studentRole=roleId(roles,"STUDENT");applicantRole=roleId(roles,"APPLICANT");systemRole=roleId(roles,"SYSTEM_ADMIN");
        var created=write("POST","/api/m2/admin/departments",Map.of("code","MGMT","name","管理测试院系"),admin,key());
        assertEquals(201,created.statusCode()); department=value(created,"id");
    }
    @AfterAll void stop() throws Exception { try { stopBackend(); } finally { if(roomServer!=null) roomServer.stop(0); } }

    @Test @Order(1) void departmentsValidateQueriesAndKeepDisabledHistoricalBindings() throws Exception {
        String requestKey=key();Map<String,Object> body=Map.of("code","HISTORY","name","历史院系");
        var created=write("POST","/api/m2/admin/departments",body,admin,requestKey);assertEquals(201,created.statusCode());String id=value(created,"id");
        var replay=write("POST","/api/m2/admin/departments",body,admin,requestKey);assertEquals(201,replay.statusCode());assertEquals(json.readTree(created.body()),json.readTree(replay.body()));assertEquals("true",replay.headers().firstValue("Idempotency-Replayed").orElseThrow());
        assertCode(write("POST","/api/m2/admin/departments",Map.of("code","OTHER","name","不同内容"),admin,requestKey),409,"IDEMPOTENCY_CONFLICT");
        assertCode(write("POST","/api/m2/admin/departments",body,admin,key()),409,"DUPLICATE_CODE");
        User existing=createUser("history-user",id,List.of(studentRole));Session student=login(existing.code(),existing.password());
        var disabled=write("PATCH","/api/m2/admin/departments/"+id,Map.of("expectedVersion","1","enabled",false),admin,key());assertEquals(200,disabled.statusCode());
        var defaults=request("GET","/api/m2/departments?keyword=HISTORY",null,student,null);assertEquals(0,JsonPath.<Integer>read(defaults.body(),"$.data.total"));
        var historical=request("GET","/api/m2/departments?keyword=HISTORY&enabled=false&pageSize=1",null,student,null);assertEquals(1,JsonPath.<Integer>read(historical.body(),"$.data.total"));
        assertEquals(200,request("GET","/api/m2/auth/me",null,student,null).statusCode());
        assertCode(write("POST","/api/m2/admin/accounts",accountBody("blocked-department",secret(),id,List.of(studentRole)),admin,key()),400,"VALIDATION_ERROR");
        assertCode(write("PATCH","/api/m2/admin/departments/"+id,Map.of("expectedVersion","1","name","过期修改"),admin,key()),409,"VERSION_CONFLICT");
        for(String query:List.of("page=0","page=1&page=2","pageSize=101","keyword=","enabled=1","unknown=true")) assertCode(request("GET","/api/m2/departments?"+query,null,student,null),400,"VALIDATION_ERROR");
        assertCode(write("POST","/api/m2/admin/departments",Map.of("code","BAD","name","未知字段","enabled",false),admin,key()),400,"VALIDATION_ERROR");
    }
    @Test @Order(2) void accountCreationPaginationAndPermissionBeforePayloadValidation() throws Exception {
        User user=createUser("student-api",department,List.of(studentRole,studentRole));Session student=login(user.code(),user.password());
        var current=request("GET","/api/m2/admin/accounts/"+user.id(),null,admin,null);assertEquals(1,JsonPath.<List<?>>read(current.body(),"$.data.roleIds").size());
        assertFalse(current.body().contains("password"));assertFalse(current.body().contains(user.password()));
        for(String query:List.of("keyword=student-api","departmentId="+department+"&roleId="+studentRole+"&enabled=true")) {
            var response=request("GET","/api/m2/admin/accounts?"+query+"&pageSize=1",null,admin,null);assertEquals(200,response.statusCode());assertTrue(JsonPath.<Integer>read(response.body(),"$.data.total")>=1);assertEquals(1,JsonPath.<List<?>>read(response.body(),"$.data.items").size());
        }
        var beyond=request("GET","/api/m2/admin/accounts?keyword=student-api&page=999",null,admin,null);assertEquals(1,JsonPath.<Integer>read(beyond.body(),"$.data.total"));assertEquals(List.of(),JsonPath.read(beyond.body(),"$.data.items"));
        assertCode(request("GET","/api/m2/admin/accounts?unknown=true",null,student,null),403,"FORBIDDEN");
        assertCode(write("POST","/api/m2/admin/accounts",Map.of("unknown",true),student,key()),403,"FORBIDDEN");
        assertCode(request("GET","/api/m2/admin/accounts",null,null,null),401,"UNAUTHENTICATED");
        assertCode(write("POST","/api/m2/admin/accounts",accountBody("invalid-role",secret(),department,List.of(key())),admin,key()),400,"VALIDATION_ERROR");
        var noKey=request("POST","/api/m2/admin/accounts",accountBody("no-key",secret(),department,List.of()),admin,null);assertCode(noKey,400,"VALIDATION_ERROR");
        assertCode(write("POST","/api/m2/admin/accounts",accountBody("no-csrf",secret(),department,List.of()),new Session(admin.token(),null),key()),403,"FORBIDDEN");
        for(String bad:List.of("a".repeat(7),"a".repeat(25))) assertCode(write("POST","/api/m2/admin/accounts",accountBody("bad-password",bad,department,List.of()),admin,key()),400,"VALIDATION_ERROR");
        assertCode(request("GET","/api/m2/admin/accounts/"+key(),null,admin,null),404,"NOT_FOUND");
    }
    @Test @Order(3) void accountAndRoleChangesRevokeSessionsAndDetectStaleVersions() throws Exception {
        User user=createUser("role-change",department,List.of(studentRole));Session original=login(user.code(),user.password());
        var replaced=write("PUT","/api/m2/admin/accounts/"+user.id()+"/roles",Map.of("expectedVersion","1","roleIds",List.of(applicantRole)),admin,key());assertEquals(200,replaced.statusCode());assertEquals("2",value(replaced,"permissionVersion"));
        assertCode(request("GET","/api/m2/auth/me",null,original,null),401,"UNAUTHENTICATED");
        Session updated=login(user.code(),user.password());var me=request("GET","/api/m2/auth/me",null,updated,null);
        List<Map<String,Object>> grants=JsonPath.read(me.body(),"$.data.grants");assertTrue(grants.stream().anyMatch(g->g.get("action").equals("room:apply")));assertFalse(grants.stream().anyMatch(g->g.get("action").equals("system:manage")));
        assertCode(write("PATCH","/api/m2/admin/accounts/"+user.id(),Map.of("expectedVersion","1","displayName","旧版本"),admin,key()),409,"VERSION_CONFLICT");
        var disabled=write("PATCH","/api/m2/admin/accounts/"+user.id(),Map.of("expectedVersion","2","enabled",false),admin,key());assertEquals(200,disabled.statusCode());
        assertCode(request("GET","/api/m2/auth/me",null,updated,null),401,"UNAUTHENTICATED");assertCode(loginResponse(user.code(),user.password()),401,"UNAUTHENTICATED");
        assertEquals(200,write("PATCH","/api/m2/admin/accounts/"+user.id(),Map.of("expectedVersion","3","enabled",true),admin,key()).statusCode());assertEquals(200,loginResponse(user.code(),user.password()).statusCode());
        assertCode(write("PATCH","/api/m2/admin/accounts/"+user.id(),Map.of("expectedVersion","4","userCode","immutable"),admin,key()),400,"VALIDATION_ERROR");
        assertCode(write("PATCH","/api/m2/admin/accounts/"+user.id(),Map.of("expectedVersion",4,"enabled",false),admin,key()),400,"VALIDATION_ERROR");
    }
    @Test @Order(4) void durableCreationIntentSurvivesExpiryAndRestartWithoutStoringSecrets() throws Exception {
        String password=secret();passwords.add(password);Map<String,Object> body=accountBody("durable-user",password,department,List.of(studentRole));String requestKey=key();
        var created=write("POST","/api/m2/admin/accounts",body,admin,requestKey);assertEquals(201,created.statusCode());String id=value(created,"id");
        assertEquals(201,write("POST","/api/m2/admin/accounts",body,admin,requestKey).statusCode());
        assertEquals(200,write("PATCH","/api/m2/admin/accounts/"+id,Map.of("expectedVersion","1","displayName","当前名称"),admin,key()).statusCode());
        sql("UPDATE m2.g5_idempotency SET expires_at=CURRENT_TIMESTAMP-interval '1 second'");
        stopBackend();startBackend();admin=login("management-admin",initial);
        var replay=write("POST","/api/m2/admin/accounts",body,admin,requestKey);assertEquals(200,replay.statusCode());assertEquals(id,value(replay,"id"));assertEquals("当前名称",value(replay,"displayName"));assertEquals("2",value(replay,"version"));
        assertEquals(1L,count("SELECT count(*) FROM m2.account WHERE user_code='durable-user'"));
        Map<String,Object> changed=new java.util.LinkedHashMap<>(body);changed.put("initialPassword",secret());assertCode(write("POST","/api/m2/admin/accounts",changed,admin,requestKey),409,"IDEMPOTENCY_CONFLICT");
        String snapshots=scalar("SELECT COALESCE(string_agg(response::text,''),'') FROM m2.g5_idempotency")+scalar("SELECT COALESCE(string_agg(event::text,''),'') FROM m2.g5_audit_event");
        for(String secret:passwords) assertFalse(snapshots.contains(secret),"Responses, cache and audit must not contain passwords");
        assertFalse(snapshots.contains("password_hash"));assertFalse(snapshots.contains("pbkdf2@SpringSecurity"));assertFalse(snapshots.contains(hmac));
        assertEquals(count("SELECT count(*) FROM m2.g5_audit_event"),count("SELECT count(*) FROM m2.g5_outbox"));
    }
    @Test @Order(5) void outboxFailureRollsBackAccountBindingsAndCreationIntent() throws Exception {
        sql("CREATE FUNCTION m2.fail_management_outbox() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'injected isolated outbox failure'; END; $$");
        sql("CREATE TRIGGER fail_management_outbox BEFORE INSERT ON m2.g5_outbox FOR EACH ROW EXECUTE FUNCTION m2.fail_management_outbox()");
        try { assertCode(write("POST","/api/m2/admin/accounts",accountBody("rollback-user",secret(),department,List.of(studentRole)),admin,key()),503,"AUTH_UNAVAILABLE"); }
        finally { sql("DROP TRIGGER fail_management_outbox ON m2.g5_outbox");sql("DROP FUNCTION m2.fail_management_outbox()"); }
        assertEquals(0L,count("SELECT count(*) FROM m2.account WHERE user_code='rollback-user'"));
        assertEquals(0L,count("SELECT count(*) FROM m2.g5_creation_intent i LEFT JOIN m2.account a ON a.id=i.object_id WHERE i.object_kind='ACCOUNT' AND a.id IS NULL"));
    }
    @Test @Order(99) void lastEffectiveAdministratorIsProtectedUnderConcurrentSelfDisable() throws Exception {
        String adminId=value(request("GET","/api/m2/auth/me",null,admin,null),"userId");
        String currentVersion=value(request("GET","/api/m2/admin/accounts/"+adminId,null,admin,null),"version");
        assertCode(write("PATCH","/api/m2/admin/accounts/"+adminId,Map.of("expectedVersion",currentVersion,"enabled",false),admin,key()),409,"LAST_ADMIN_PROTECTED");
        assertCode(write("PUT","/api/m2/admin/accounts/"+adminId+"/roles",Map.of("expectedVersion",currentVersion,"roleIds",List.of()),admin,key()),409,"LAST_ADMIN_PROTECTED");
        User backup=createUser("backup-manager",department,List.of(systemRole));Session other=login(backup.code(),backup.password());
        try(var executor=Executors.newFixedThreadPool(2)) {
            var first=executor.submit(()->write("PATCH","/api/m2/admin/accounts/"+adminId,Map.of("expectedVersion",currentVersion,"enabled",false),admin,key()));
            var second=executor.submit(()->write("PATCH","/api/m2/admin/accounts/"+backup.id(),Map.of("expectedVersion","1","enabled",false),other,key()));
            List<Integer> statuses=new ArrayList<>(List.of(first.get().statusCode(),second.get().statusCode()));statuses.sort(Integer::compareTo);assertEquals(List.of(200,409),statuses);
        }
        assertEquals(1L,count("SELECT count(DISTINCT a.id) FROM m2.account a JOIN m2.role_binding b ON b.account_id=a.id JOIN m2.role_grant g ON g.role_id=b.role_id WHERE a.enabled AND g.action='system:manage' AND g.scope_kind='SYSTEM'"));
    }
    @Test @Order(6) void permissionCatalogAndRoleValidationAreClosedAndAuthenticated() throws Exception {
        var response=request("GET","/api/m2/admin/permissions",null,admin,null);assertEquals(200,response.statusCode());
        List<Map<String,Object>> catalog=JsonPath.read(response.body(),"$.data.items");assertEquals(38,catalog.size());
        assertEquals(List.of("SYSTEM"),catalog.stream().filter(p->p.get("action").equals("system:manage")).findFirst().orElseThrow().get("scopeKinds"));
        assertEquals(38L,count("SELECT count(*) FROM m2.permission"));
        assertCode(request("GET","/api/m2/admin/permissions?unknown=true",null,admin,null),400,"VALIDATION_ERROR");
        User user=createUser("catalog-denied",department,List.of(studentRole));Session ordinary=login(user.code(),user.password());
        assertCode(request("GET","/api/m2/admin/permissions",null,ordinary,null),403,"FORBIDDEN");
        assertCode(write("POST","/api/m2/admin/roles",Map.of("invalid",true),ordinary,key()),403,"FORBIDDEN");
        assertCode(write("POST","/api/m2/admin/roles",roleBody("NO_CSRF",List.of()),new Session(admin.token(),null),key()),403,"FORBIDDEN");
        for(Object invalid:List.of(List.of(grant("unknown:action","SCHOOL",null)),List.of(grant("system:manage","SCHOOL",null)),List.of(grant("room:read","SYSTEM",null)),List.of(grant("runtime:read","DEPARTMENT",department)),List.of(grant("room:read","DEPARTMENT",key())),List.of(Map.of("action","room:read","scope",Map.of("kind","SCHOOL","roomId",key()))),java.util.Collections.nCopies(1001,grant("room:read","SCHOOL",null)))) {
            assertCode(write("POST","/api/m2/admin/roles",Map.of("key","INVALID","name","无效授权","grants",invalid),admin,key()),400,"VALIDATION_ERROR");
        }
        assertEquals(0L,count("SELECT count(*) FROM m2.role WHERE key='INVALID'"));
        assertCode(write("PATCH","/api/m2/admin/roles/"+studentRole,Map.of("expectedVersion","1","name","不可改编号","grants",List.of(),"key","NEW"),admin,key()),400,"VALIDATION_ERROR");
        assertCode(write("PATCH","/api/m2/admin/roles/"+studentRole,Map.of("expectedVersion","1","name","不可改内置标识","grants",List.of(),"builtIn",false),admin,key()),400,"VALIDATION_ERROR");
    }
    @Test @Order(7) void roleCreationCanonicalReplayAndConflictKeepCurrentFacts() throws Exception {
        var school=grant("room:read","SCHOOL",null);var dept=grant("room:apply","DEPARTMENT",department);
        String originalKey=key();var created=write("POST","/api/m2/admin/roles",roleBody("CANONICAL",List.of(school,dept,school)),admin,originalKey);assertEquals(201,created.statusCode());
        String id=value(created,"id");assertFalse(JsonPath.<Boolean>read(created.body(),"$.data.builtIn"));assertEquals(2,JsonPath.<List<?>>read(created.body(),"$.data.grants").size());
        var replay=write("POST","/api/m2/admin/roles",roleBody("CANONICAL",List.of(dept,school)),admin,originalKey);assertEquals(201,replay.statusCode());assertEquals(json.readTree(created.body()),json.readTree(replay.body()));
        assertCode(write("POST","/api/m2/admin/roles",roleBody("CANONICAL",List.of()),admin,key()),409,"DUPLICATE_CODE");
        assertCode(write("POST","/api/m2/admin/roles",roleBody("CANONICAL",List.of()),admin,originalKey),409,"IDEMPOTENCY_CONFLICT");
        assertEquals(200,write("PATCH","/api/m2/admin/roles/"+id,Map.of("expectedVersion","1","name","修改后的角色","grants",List.of()),admin,key()).statusCode());
        assertCode(write("PATCH","/api/m2/admin/roles/"+id,Map.of("expectedVersion","1","name","过期草稿","grants",List.of(school)),admin,key()),409,"VERSION_CONFLICT");
        sql("UPDATE m2.g5_idempotency SET expires_at=CURRENT_TIMESTAMP-interval '1 second' WHERE request_key='"+originalKey+"'");
        stopBackend();startBackend();admin=login("management-admin",initial);
        var current=write("POST","/api/m2/admin/roles",roleBody("CANONICAL",List.of(school,dept)),admin,originalKey);assertEquals(200,current.statusCode());assertEquals(id,value(current,"id"));assertEquals("2",value(current,"version"));assertEquals("修改后的角色",value(current,"name"));
        assertEquals(1L,count("SELECT count(*) FROM m2.role WHERE key='CANONICAL'"));
    }
    @Test @Order(8) void sharedRoleUpdateRevokesEveryBoundSessionAndOutboxFailureRollsBack() throws Exception {
        var created=write("POST","/api/m2/admin/roles",roleBody("SHARED",List.of(grant("room:read","SCHOOL",null))),admin,key());assertEquals(201,created.statusCode());String role=value(created,"id");
        User a=createUser("shared-a",department,List.of(role)),b=createUser("shared-b",department,List.of(role)),unrelated=createUser("unrelated",department,List.of(studentRole));
        Session a1=login(a.code(),a.password()),a2=login(a.code(),a.password()),b1=login(b.code(),b.password()),untouched=login(unrelated.code(),unrelated.password());
        var updated=write("PATCH","/api/m2/admin/roles/"+role,Map.of("expectedVersion","1","name","共享申请权限","grants",List.of(grant("room:apply","DEPARTMENT",department))),admin,key());assertEquals(200,updated.statusCode());
        for(Session session:List.of(a1,a2,b1)) assertCode(request("GET","/api/m2/auth/me",null,session,null),401,"UNAUTHENTICATED");
        assertEquals(200,request("GET","/api/m2/auth/me",null,untouched,null).statusCode());
        Session fresh=login(a.code(),a.password());var me=request("GET","/api/m2/auth/me",null,fresh,null);assertEquals("2",value(me,"permissionVersion"));
        assertEquals("room:apply",JsonPath.read(me.body(),"$.data.grants[0].action"));assertEquals(department,JsonPath.read(me.body(),"$.data.grants[0].scope.departmentId"));
        sql("CREATE FUNCTION m2.fail_role_outbox() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'isolated role outbox failure'; END; $$");
        sql("CREATE TRIGGER fail_role_outbox BEFORE INSERT ON m2.g5_outbox FOR EACH ROW EXECUTE FUNCTION m2.fail_role_outbox()");
        try { assertCode(write("PATCH","/api/m2/admin/roles/"+role,Map.of("expectedVersion","2","name","必须回滚","grants",List.of()),admin,key()),503,"AUTH_UNAVAILABLE"); }
        finally { sql("DROP TRIGGER fail_role_outbox ON m2.g5_outbox");sql("DROP FUNCTION m2.fail_role_outbox()"); }
        assertEquals("2",value(request("GET","/api/m2/admin/roles/"+role,null,admin,null),"version"));assertEquals(200,request("GET","/api/m2/auth/me",null,fresh,null).statusCode());
        assertTrue(scalar("SELECT event::text FROM m2.g5_audit_event WHERE event->'audit'->>'action'='G5-P12' AND event->'aggregate'->>'id'='"+role+"' ORDER BY created_at DESC LIMIT 1").contains("grantsSHA256"));
    }
    @Test @Order(9) void roomScopeUsesAuthenticatedHttpOutsideLocksAndFailsClosed() throws Exception {
        String requestKey=key();var body=roleBody("ROOM_SCOPED",List.of(grant("room:read","ROOM",roomId)));
        var created=write("POST","/api/m2/admin/roles",body,admin,requestKey);assertEquals(201,created.statusCode());String id=value(created,"id");
        int calls=roomCalls.get();roomStatus=503;
        try {
            assertEquals(201,write("POST","/api/m2/admin/roles",body,admin,requestKey).statusCode());assertEquals(calls,roomCalls.get());
            assertCode(write("POST","/api/m2/admin/roles",roleBody("ROOM_FAILED",List.of(grant("room:read","ROOM",roomId))),admin,key()),503,"SCOPE_FACTS_UNAVAILABLE");
            assertEquals(0L,count("SELECT count(*) FROM m2.role WHERE key='ROOM_FAILED'"));
        } finally { roomStatus=200; }
        assertCode(write("POST","/api/m2/admin/roles",roleBody("ROOM_UNKNOWN",List.of(grant("room:read","ROOM",key()))),admin,key()),400,"VALIDATION_ERROR");
        assertEquals("1",value(request("GET","/api/m2/admin/roles/"+id,null,admin,null),"version"));
    }
    @Test @Order(10) void concurrentRoleRevocationsCannotRemoveLastAdministrator() throws Exception {
        List<?> manage=List.of(grant("system:manage","SYSTEM",null));
        var originalRole=request("GET","/api/m2/admin/roles/"+systemRole,null,admin,null);
        assertCode(write("PATCH","/api/m2/admin/roles/"+systemRole,Map.of("expectedVersion",value(originalRole,"version"),"name","系统管理员","grants",List.of()),admin,key()),409,"LAST_ADMIN_PROTECTED");
        String roleA=value(write("POST","/api/m2/admin/roles",roleBody("ADMIN_A",manage),admin,key()),"id"),roleB=value(write("POST","/api/m2/admin/roles",roleBody("ADMIN_B",manage),admin,key()),"id");
        User a=createUser("role-admin-a",department,List.of(roleA)),b=createUser("role-admin-b",department,List.of(roleB));Session sa=login(a.code(),a.password()),sb=login(b.code(),b.password());
        String initialId=value(request("GET","/api/m2/auth/me",null,admin,null),"userId");
        String initialVersion=value(request("GET","/api/m2/admin/accounts/"+initialId,null,admin,null),"version");
        assertEquals(200,write("PUT","/api/m2/admin/accounts/"+initialId+"/roles",Map.of("expectedVersion",initialVersion,"roleIds",List.of()),admin,key()).statusCode());
        try(var executor=Executors.newFixedThreadPool(2)) {
            var first=executor.submit(()->write("PATCH","/api/m2/admin/roles/"+roleA,Map.of("expectedVersion","1","name","ADMIN_A","grants",List.of()),sa,key()));
            var second=executor.submit(()->write("PATCH","/api/m2/admin/roles/"+roleB,Map.of("expectedVersion","1","name","ADMIN_B","grants",List.of()),sb,key()));
            var ra=first.get();var rb=second.get();List<Integer> statuses=new ArrayList<>(List.of(ra.statusCode(),rb.statusCode()));statuses.sort(Integer::compareTo);assertEquals(List.of(200,409),statuses);
            Session survivor=ra.statusCode()==409?sa:sb;User survivorUser=ra.statusCode()==409?a:b;
            assertEquals(1L,count("SELECT count(DISTINCT a.id) FROM m2.account a JOIN m2.role_binding b ON b.account_id=a.id JOIN m2.role_grant g ON g.role_id=b.role_id WHERE a.enabled AND g.action='system:manage' AND g.scope_kind='SYSTEM'"));
            String version=value(request("GET","/api/m2/admin/accounts/"+initialId,null,survivor,null),"version");
            assertEquals(200,write("PUT","/api/m2/admin/accounts/"+initialId+"/roles",Map.of("expectedVersion",version,"roleIds",List.of(systemRole)),survivor,key()).statusCode());
            admin=login("management-admin",initial);
            assertEquals(200,write("PATCH","/api/m2/admin/accounts/"+survivorUser.id(),Map.of("expectedVersion","1","enabled",false),admin,key()).statusCode());
        }
    }
    private Map<String,Object> roleBody(String key,List<?> grants) { return Map.of("key",key,"name",key,"grants",grants); }
    private Map<String,Object> grant(String action,String kind,String id) { return Map.of("action",action,"scope",id==null?Map.of("kind",kind):Map.of("kind",kind,kind.equals("ROOM")?"roomId":"departmentId",id)); }
    private void startRoomBoundary() throws Exception {
        roomServer=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        roomServer.createContext("/internal/rooms/",exchange->{
            roomCalls.incrementAndGet();int code=roomStatus;String response="{}";
            try {
                assertEquals("Bearer "+roomSecret,exchange.getRequestHeaders().getFirst("Authorization"));assertNull(exchange.getRequestHeaders().getFirst("Cookie"));assertNull(exchange.getRequestHeaders().getFirst("X-Actor-Session"));
                assertTrue(exchange.getRequestURI().getQuery().matches("purpose=AUTHORIZATION&traceId=[0-9a-f-]{36}"));
                // The external boundary can acquire this row NOWAIT: G5 holds no management lock during HTTP.
                sql("SELECT id FROM m2.g5_admin_guard WHERE id=1 FOR UPDATE NOWAIT");
                if(!exchange.getRequestURI().getPath().equals("/internal/rooms/"+roomId+"/scope-facts")) code=404;
                if(code==200) response=json.writeValueAsString(Map.of("data",Map.of("roomId",roomId,"schoolWide",false,"deptId",department,"enabled",false,"roomVersion","1","asOf","2026-11-03T01:00:00.000+08:00"),"traceId",key()));
            } catch(Throwable failure) { code=503; }
            byte[] bytes=response.getBytes(java.nio.charset.StandardCharsets.UTF_8);exchange.getResponseHeaders().add("Content-Type","application/json");exchange.sendResponseHeaders(code,bytes.length);try(var out=exchange.getResponseBody()){out.write(bytes);}
        });
        roomServer.start();
    }
    private String roleId(List<Map<String,Object>> rows,String key) { return (String)rows.stream().filter(r->r.get("key").equals(key)).findFirst().orElseThrow().get("id"); }
    private User createUser(String code,String department,List<String> roles) throws Exception {String password=secret();passwords.add(password);var response=write("POST","/api/m2/admin/accounts",accountBody(code,password,department,roles),admin,key());assertEquals(201,response.statusCode());return new User(code,password,value(response,"id"));}
    private Map<String,Object> accountBody(String code,String password,String department,List<String> roles) {return Map.of("userCode",code,"displayName","合成验证账户","departmentId",department,"email",code+"@example.invalid","initialPassword",password,"roleIds",roles);}
    private static String secret() {return UUID.randomUUID().toString().replace("-","").substring(0,24);}
    private static String key() {return UUID.randomUUID().toString();}
    private String value(HttpResponse<String> response,String field) {return JsonPath.read(response.body(),"$.data."+field);}
    private HttpResponse<String> loginResponse(String code,String password) throws Exception {return request("POST","/api/m2/auth/login",Map.of("account",code,"password",password),null,null);}
    private Session login(String code,String password) throws Exception {var response=loginResponse(code,password);assertEquals(200,response.statusCode());String cookie=response.headers().firstValue("Set-Cookie").orElseThrow();return new Session(cookie.substring(11,cookie.indexOf(';')),JsonPath.read(response.body(),"$.data.csrfToken"));}
    private HttpResponse<String> write(String method,String path,Map<String,Object> body,Session session,String key) throws Exception {return request(method,path,body,session,Map.of("Idempotency-Key",key));}
    private HttpResponse<String> request(String method,String path,Object body,Session session,Map<String,String> headers) throws Exception {
        var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(15));
        if(session!=null){builder.header("Cookie","PJ_SESSION="+session.token());if(session.csrf()!=null)builder.header("X-CSRF-Token",session.csrf());}
        if(headers!=null)headers.forEach(builder::header);
        if(body!=null)builder.header("Content-Type","application/json");
        builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        return http.send(builder.build(),HttpResponse.BodyHandlers.ofString());
    }
    private void assertCode(HttpResponse<String> response,int status,String code){assertEquals(status,response.statusCode(),"Expected "+code);assertEquals(code,JsonPath.read(response.body(),"$.code"));}
    private void startBackend() throws Exception {
        port=freePort();do{managementPort=freePort();}while(managementPort==port);
        Path jar=Path.of(System.getProperty("appJar")).toAbsolutePath();
        String executable=System.getProperty("os.name").startsWith("Windows")?"java.exe":"java";
        var builder=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin",executable).toString(),"-jar",jar.toString());
        builder.environment().putAll(Map.of("DB_URL",database,"DB_MIGRATION_URL",database,"APP_BIND_ADDRESS","127.0.0.1","APP_PORT",Integer.toString(port),"MANAGEMENT_PORT",Integer.toString(managementPort),"G5_BOOTSTRAP_ENABLED","true","G5_BOOTSTRAP_ACCOUNT","management-admin","G5_BOOTSTRAP_PASSWORD",initial,"G5_IDEMPOTENCY_HMAC_KEY",hmac));
        builder.environment().put("G4_BASE_URL","http://127.0.0.1:"+roomServer.getAddress().getPort());
        builder.environment().put("G4_SERVICE_G5_TOKEN",roomSecret);
        builder.redirectErrorStream(true).redirectOutput(jar.getParent().resolve("management-it-"+port+".log").toFile());process=builder.start();
        long deadline=System.nanoTime()+Duration.ofSeconds(60).toNanos();while(System.nanoTime()<deadline){assertTrue(process.isAlive(),"Management backend exited; inspect ignored log");try{var response=http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+managementPort+"/actuator/health/readiness")).GET().build(),HttpResponse.BodyHandlers.ofString());if(response.statusCode()==200)return;}catch(IOException ignored){}Thread.sleep(200);}fail("Management backend readiness timed out");
    }
    private void stopBackend() throws Exception {if(process!=null){process.destroy();if(!process.waitFor(15,TimeUnit.SECONDS)){process.destroyForcibly();assertTrue(process.waitFor(10,TimeUnit.SECONDS));}}}
    private int freePort() throws IOException {try(var socket=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))){return socket.getLocalPort();}}
    private void sql(String statement) throws Exception {try(var connection=DriverManager.getConnection(database,System.getenv("DB_MIGRATION_USERNAME"),System.getenv("DB_MIGRATION_PASSWORD"));var sql=connection.createStatement()){sql.execute(statement);}}
    private String scalar(String statement) throws Exception {try(var connection=DriverManager.getConnection(database,System.getenv("DB_MIGRATION_USERNAME"),System.getenv("DB_MIGRATION_PASSWORD"));var sql=connection.createStatement();var rows=sql.executeQuery(statement)){assertTrue(rows.next());return rows.getString(1);}}
    private long count(String statement) throws Exception {return Long.parseLong(scalar(statement));}
}
