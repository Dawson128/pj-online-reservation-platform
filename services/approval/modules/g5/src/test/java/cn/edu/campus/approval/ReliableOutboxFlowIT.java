package cn.edu.campus.approval;

import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.sql.DriverManager;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import com.sun.net.httpserver.HttpServer;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.*;
import tools.jackson.databind.json.JsonMapper;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.mock.env.MockEnvironment;
import cn.edu.campus.approval.g5.api.*;
import cn.edu.campus.approval.g5.application.*;
import cn.edu.campus.approval.g5.domain.*;
import cn.edu.campus.approval.g5.persistence.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real PostgreSQL, real G5 transaction/ports/dispatcher and packaged process; only G6 is a controlled HTTP edge. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ReliableOutboxFlowIT {
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();private final JsonMapper json=new JsonMapper();
    private final String password=UUID.randomUUID().toString().replace("-","").substring(0,24),hmac=key()+key(),g6Token=key()+key();
    private final Map<String,String> serviceTokens=new LinkedHashMap<>();private final AtomicReference<String> mode=new AtomicReference<>("OK");
    private final Map<UUID,String> accepted=new ConcurrentHashMap<>();private final Map<UUID,AtomicInteger> requests=new ConcurrentHashMap<>();private final List<String> edgeErrors=new CopyOnWriteArrayList<>();private final AtomicReference<UUID> holdId=new AtomicReference<>();private CountDownLatch held,release;
    private final List<OutboxDispatcher> workers=new ArrayList<>();private final List<Map<String,Object>> evidence=new ArrayList<>();
    private String database,adminId;private Process process;private int port,managementPort;private Session admin;
    private HttpServer g6;private ExecutorService executor;private JdbcTemplate jdbc;private TransactionTemplate tx;private DeliveryRepository deliveries;private IdentityRepository identityRepository;private IdentityService identity;private ManagementRepository management;private AuthAudit audit;private ConfigurationService configuration;private OperationService operations;private OutboxReadPort references;private MockEnvironment env;
    private UUID legacyPending=UUID.randomUUID(),legacyDelivered=UUID.randomUUID(),legacyBlocked=UUID.randomUUID();
    private record Session(String token,String csrf){@Override public String toString(){return "Session[REDACTED]";}}
    private static String key(){return UUID.randomUUID().toString();}
    @BeforeAll void start()throws Exception{
        database=System.getenv("G5_OUTBOX_TEST_DB_URL");assertTrue(database!=null&&database.matches("jdbc:postgresql://[^/]+/pj_g5_auth_it_outbox_[a-zA-Z0-9_]+"));
        for(String variable:List.of("DB_URL","G5_MANAGEMENT_TEST_DB_URL","G5_CONFIG_TEST_DB_URL","G5_RESOURCE_FACTS_TEST_DB_URL","G5_BUSINESS_TEST_DB_URL"))assertNotEquals(database,System.getenv(variable));
        var ds=new DriverManagerDataSource(database,System.getenv("DB_MIGRATION_USERNAME"),System.getenv("DB_MIGRATION_PASSWORD"));jdbc=new JdbcTemplate(ds);tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
        Path migrations=Path.of(System.getProperty("appJar")).getParent().resolve("../../../db/m2/g5").normalize();
        Flyway.configure().dataSource(ds).schemas("m2").defaultSchema("m2").locations("filesystem:"+migrations).target("20261103.050000.5.005").load().migrate();
        insert(legacyPending,null,"PENDING");insert(legacyDelivered,null,"DELIVERED");insert(legacyBlocked,null,"BLOCKED");
        startG6();startBackend(false);admin=login("outbox-manager",password);adminId=JsonPath.read(request("GET","/api/m2/auth/me",null,admin,null).body(),"$.data.userId");
        identityRepository=new IdentityRepository(jdbc);management=new ManagementRepository(jdbc);audit=new AuthAudit(jdbc,json,identityRepository);identity=new IdentityService(identityRepository,new AuthRateLimiter(jdbc),audit,new Credentials(),tx);configuration=new ConfigurationService(management,identity,tx);deliveries=new DeliveryRepository(jdbc);
        env=new MockEnvironment().withProperty("G5_IDEMPOTENCY_HMAC_KEY",hmac).withProperty("G6_BASE_URL","http://127.0.0.1:"+g6.getAddress().getPort()).withProperty("G6_SERVICE_G5_TOKEN",g6Token);
        operations=new OperationService(deliveries,new AuthorizationRepository(jdbc,identityRepository),management,audit,json,env,tx);references=new OutboxReferenceService(operations);
        jdbc.update("UPDATE m2.business_config SET scan_interval_seconds=1 WHERE id=1");
    }
    @AfterAll void stop()throws Exception{stopWorkers();stopBackend();if(g6!=null)g6.stop(0);if(executor!=null)executor.shutdownNow();assertEquals(List.of(),edgeErrors);Path file=Path.of(System.getProperty("appJar")).getParent().resolve("local-port-results.json");Files.writeString(file,json.writeValueAsString(evidence));}
    private Principal principal(){return identity.introspect(admin.token());}
    private OutboxDispatcher worker(){var worker=new OutboxDispatcher(deliveries,configuration,env,json,tx);workers.add(worker);worker.start();return worker;}
    private void stopWorkers()throws Exception{for(var worker:workers)worker.stop();workers.clear();Thread.sleep(150);}
    private void quiet()throws Exception{stopWorkers();mode.set("OK");jdbc.update("UPDATE m2.g5_operation SET status='SUCCEEDED',lease_token=NULL,lease_until=NULL,next_retry_at=NULL,error_code=NULL,needs_attention=false");jdbc.update("UPDATE m2.g5_outbox SET status='DELIVERED',delivered_at=coalesce(delivered_at,clock_timestamp())");}
    private UUID append(){UUID id=UUID.randomUUID();tx.executeWithoutResult(s->insert(id,UUID.fromString(adminId),"PENDING"));return id;}
    private void insert(UUID id,UUID actor,String state){
        String now=OffsetDateTime.now().toString();UUID trace=UUID.randomUUID();var source=Map.of("type","SYSTEM","id",id);Map<String,Object> a=new LinkedHashMap<>();a.put("id",id);a.put("producer","G5");a.put("source",source);a.put("roomId",null);a.put("scopeKind","SYSTEM");a.put("actorId",actor);a.put("actorKind",actor==null?"SYSTEM":"USER");a.put("action","auth:request-denied");a.put("before",List.of());a.put("after",List.of());a.put("reason",null);a.put("result","SUCCESS");a.put("errorCode",null);a.put("effectiveAt",now);a.put("processedAt",now);a.put("traceId",trace);a.put("operationId",null);
        Map<String,Object> e=new LinkedHashMap<>();e.put("eventId",id);e.put("eventType","AuditRecorded");e.put("schemaVersion",1);e.put("producer","G5");e.put("aggregate",source);e.put("aggregateVersion","1");e.put("roomId",null);e.put("occurredAt",now);e.put("actorId",actor);e.put("actorKind",actor==null?"SYSTEM":"USER");e.put("traceId",trace);e.put("operationId",null);e.put("audit",a);e.put("payload",Map.of());
        jdbc.update("INSERT INTO m2.g5_audit_event(event_id,event) VALUES (?,?::jsonb)",id,json.writeValueAsString(e));jdbc.update("INSERT INTO m2.g5_outbox(event_id,status,delivered_at) VALUES (?,?,CASE WHEN ? THEN clock_timestamp() ELSE NULL END)",id,state,state.equals("DELIVERED"));
    }
    private void state(UUID id,String state){jdbc.update("UPDATE m2.g5_operation SET status=?,error_code='DEPENDENCY_UNAVAILABLE',next_retry_at=clock_timestamp()+interval '60 seconds' WHERE id=?",state,id);}
    private DeliveryRepository.Row row(UUID id){return deliveries.get(id,false);}
    private void await(UUID id,String state,int seconds)throws Exception{long until=System.nanoTime()+Duration.ofSeconds(seconds).toNanos();while(System.nanoTime()<until){if(state.equals(row(id).status()))return;Thread.sleep(100);}assertEquals(state,row(id).status());}
    private void code(String expected,Runnable action){var error=assertThrows(IdentityException.class,action::run);assertEquals(expected,error.code());}
    private int hits(UUID id){return requests.getOrDefault(id,new AtomicInteger()).get();}
    private void capture(String schema,Object value){evidence.add(Map.of("schema",schema,"data",json.convertValue(value,Map.class)));}
    private void startG6()throws Exception{
        g6=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);executor=Executors.newCachedThreadPool();g6.setExecutor(executor);
        g6.createContext("/internal/events",exchange->{try{
            if(!exchange.getRequestMethod().equals("POST")||!("Bearer "+g6Token).equals(exchange.getRequestHeaders().getFirst("Authorization")))throw new AssertionError("G6 service authentication");
            if(exchange.getRequestHeaders().containsKey("Cookie")||exchange.getRequestHeaders().containsKey("X-Actor-Session"))throw new AssertionError("User credential leaked");
            String body=new String(exchange.getRequestBody().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);var event=json.readTree(body);UUID id=UUID.fromString(event.path("eventId").asText());requests.computeIfAbsent(id,n->new AtomicInteger()).incrementAndGet();String current=mode.get();
            if(current.equals("FAIL")){exchange.sendResponseHeaders(503,-1);return;}if(current.equals("REJECT")){exchange.sendResponseHeaders(400,-1);return;}
            if(current.equals("SLOW")){Thread.sleep(5500);}if(current.equals("BADJSON")){byte[] bytes="invalid".getBytes();exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);return;}
            if(current.equals("NOLOCK")){try(var connection=DriverManager.getConnection(database,System.getenv("DB_MIGRATION_USERNAME"),System.getenv("DB_MIGRATION_PASSWORD"));var statement=connection.createStatement()){statement.execute("SET lock_timeout='1s'");statement.executeUpdate("UPDATE m2.g5_operation SET updated_at=updated_at WHERE id='"+id+"'");}}
            String prior=accepted.putIfAbsent(id,body);if(prior!=null&&!json.readTree(prior).equals(event))throw new AssertionError("Event changed across attempts");
            if(id.equals(holdId.get())){held.countDown();release.await(15,TimeUnit.SECONDS);holdId.set(null);}
            if(current.equals("DROP")){return;}
            Map<String,Object> receipt=Map.of("eventId",current.equals("WRONG")?UUID.randomUUID():id,"duplicate",prior!=null,"acceptedAt",OffsetDateTime.now().toString());byte[] bytes=json.writeValueAsBytes(Map.of("data",receipt,"traceId",UUID.randomUUID()));exchange.getResponseHeaders().add("Content-Type","application/json");exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);
        }catch(IOException closed){/* deliberate timeout/disconnect */}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}catch(Throwable bad){edgeErrors.add(bad.toString());exchange.sendResponseHeaders(500,-1);}finally{exchange.close();}});g6.start();
    }
    @Test @Order(1) void forwardMigrationBackfillsEveryHistoricalOutboxAndNewEventsAreAtomic()throws Exception{
        assertEquals("PENDING",row(legacyPending).status());assertEquals("SUCCEEDED",row(legacyDelivered).status());assertEquals("BLOCKED",row(legacyBlocked).status());assertTrue(row(legacyBlocked).attention());assertEquals(legacyPending,row(legacyPending).sourceId());
        assertEquals(count("SELECT count(*) FROM m2.g5_outbox"),count("SELECT count(*) FROM m2.g5_operation"));
        UUID id=UUID.randomUUID();assertThrows(RuntimeException.class,()->tx.executeWithoutResult(s->{insert(id,null,"PENDING");throw new RuntimeException("rollback fixture");}));assertNull(row(id));assertEquals(0,count("SELECT count(*) FROM m2.g5_outbox WHERE event_id='"+id+"'"));
        quiet();UUID fresh=append();assertEquals("PENDING",row(fresh).status());assertEquals(0,row(fresh).attempts());
    }
    @Test @Order(2) void portsRecheckCurrentDatabaseGrantsAndDoNotTrustSuppliedPrincipal()throws Exception{
        quiet();UUID target=append();Principal admin=principal();var fake=new Principal(admin.userId(),admin.displayName(),admin.deptId(),admin.accountVersion(),"999",admin.grants(),false,admin.expiresAt());code("VERSION_CONFLICT",()->operations.get(fake,target));
        UUID account=UUID.randomUUID(),role=UUID.randomUUID();jdbc.update("INSERT INTO m2.account(id,user_code,display_name,department_id,email,password_hash) SELECT ?,'outbox-reader','只读夹具',department_id,'reader@example.invalid',password_hash FROM m2.account WHERE id=?",account,admin.userId());jdbc.update("INSERT INTO m2.role(id,key,name) VALUES (?,'OUTBOX_SCOPE','操作房间权限')",role);jdbc.update("INSERT INTO m2.role_binding(id,account_id,role_id) VALUES (?,?,?)",UUID.randomUUID(),account,role);UUID room=UUID.randomUUID();jdbc.update("INSERT INTO m2.role_grant(id,role_id,action,scope_kind,room_id) VALUES (?,?,?,'ROOM',?)",UUID.randomUUID(),role,"operation:read",room);
        var forged=new Principal(account,"夹具",admin.deptId(),"1","1",admin.grants(),false,admin.expiresAt());code("FORBIDDEN",()->operations.list(forged,Map.of()));code("NOT_FOUND",()->operations.get(forged,target));code("NOT_FOUND",()->references.get(forged,target));assertEquals(0,operations.list(forged,Map.of("roomId",room.toString())).total());code("FORBIDDEN",()->operations.metrics(forged,UUID.randomUUID()));
        UUID own=UUID.randomUUID();tx.executeWithoutResult(s->insert(own,account,"PENDING"));assertEquals(own,operations.get(forged,own).id());code("FORBIDDEN",()->operations.retry(forged,own,Map.of("expectedVersion","1","reason","权限不足验证"),UUID.randomUUID()));jdbc.update("UPDATE m2.account SET enabled=false WHERE id=?",account);code("UNAUTHENTICATED",()->operations.get(forged,own));
    }
    @Test @Order(3) void listFiltersBeforeCountPageAndMetricsKeepOriginalAgeAndSafeReferences()throws Exception{
        quiet();List<UUID> ids=new ArrayList<>();tx.executeWithoutResult(s->{for(int i=0;i<105;i++){UUID id=UUID.randomUUID();insert(id,UUID.fromString(adminId),"PENDING");ids.add(id);}});state(ids.getFirst(),"BLOCKED");
        var page=operations.list(principal(),Map.of("status","PENDING","page",2,"pageSize",100));assertEquals(104,page.total());assertEquals(4,page.items().size());capture("Page",page);capture("Operation",operations.get(principal(),ids.getFirst()));
        UUID oldest=ids.get(1);jdbc.update("UPDATE m2.g5_audit_event SET event=jsonb_set(event,'{occurredAt}',to_jsonb(?::text)) WHERE event_id=?",OffsetDateTime.now().minusHours(2).toString(),oldest);jdbc.update("UPDATE m2.g5_operation SET accepted_at=clock_timestamp()-interval '30 minutes' WHERE id=?",oldest);
        var metrics=operations.metrics(principal(),null);assertEquals(105,metrics.operations().pendingCount());assertTrue(metrics.operations().oldestOutboxAgeSeconds()>=7200);assertEquals("OUTBOX",metrics.operations().oldestPendingRef().kind());assertEquals(oldest,metrics.operations().oldestPendingRef().id());assertNull(metrics.notifications());capture("Metrics",metrics);capture("OutboxReference",references.get(principal(),oldest));
        assertEquals(1,operations.list(principal(),Map.of("sourceId",ids.getFirst().toString())).total());assertEquals(0,operations.list(principal(),Map.of("category","RESOURCE")).total());code("VALIDATION_ERROR",()->operations.list(principal(),Map.of("ownerGroup","G2")));code("VALIDATION_ERROR",()->operations.list(principal(),Map.of("page",0)));code("VALIDATION_ERROR",()->operations.list(principal(),Map.of("createdFrom",OffsetDateTime.now().toString())));
        var snapshot=new TransactionTemplate(tx.getTransactionManager());snapshot.setReadOnly(true);snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);snapshot.executeWithoutResult(s->{assertEquals(104,operations.list(principal(),Map.of("status","PENDING")).total());assertEquals(105,operations.metrics(principal(),null).operations().pendingCount());});
    }
    @Test @Order(4) void manualRecoveryHasVersionIdempotencyAuditAndNeverReplaysManagementChanges()throws Exception{
        quiet();UUID id=append();state(id,"BLOCKED");String original=scalar("SELECT event::text FROM m2.g5_audit_event WHERE event_id='"+id+"'");long accounts=count("SELECT count(*) FROM m2.account");String config=scalar("SELECT version::text FROM m2.business_config");Principal actor=principal();UUID key=UUID.randomUUID();Map<String,Object> body=Map.of("expectedVersion",Long.toString(row(id).version()),"reason","依赖恢复，重排原事件");var first=operations.retry(actor,id,body,key);assertEquals("PENDING",first.status());assertEquals(first,operations.retry(actor,id,body,key));assertEquals(1,count("SELECT count(*) FROM m2.g5_audit_event WHERE event->'audit'->>'action'='G5-L05' AND event->'audit'->>'result'='SUCCESS'"));
        code("IDEMPOTENCY_CONFLICT",()->operations.retry(actor,id,Map.of("expectedVersion",body.get("expectedVersion"),"reason","更改正文"),key));code("VERSION_CONFLICT",()->operations.retry(actor,id,body,UUID.randomUUID()));assertEquals(original,scalar("SELECT event::text FROM m2.g5_audit_event WHERE event_id='"+id+"'"));assertEquals(accounts,count("SELECT count(*) FROM m2.account"));assertEquals(config,scalar("SELECT version::text FROM m2.business_config"));
        worker();await(id,"SUCCEEDED",10);stopWorkers();assertEquals(first,operations.retry(actor,id,body,key));jdbc.update("UPDATE m2.g5_idempotency SET expires_at=clock_timestamp()-interval '1 second' WHERE request_key=?",key);assertEquals("SUCCEEDED",operations.retry(actor,id,body,key).status());code("STATE_CONFLICT",()->operations.retry(actor,id,Map.of("expectedVersion",Long.toString(row(id).version()),"reason","禁止重复成功操作"),UUID.randomUUID()));
        String events=scalar("SELECT coalesce(string_agg(event::text,''),'') FROM m2.g5_audit_event");for(String secret:List.of(password,hmac,g6Token,admin.token()))assertFalse(events.contains(secret));assertFalse(json.writeValueAsString(operations.get(actor,id)).contains("payload"));
    }
    @Test @Order(5) void expiredLeaseReclaimsWithFencingAndMonotonicAttemptHistory()throws Exception{
        quiet();UUID id=append();var first=tx.execute(s->deliveries.claim());assertEquals(id,first.id());assertTrue(Boolean.TRUE.equals(tx.execute(s->deliveries.heartbeat(first))));OffsetDateTime lease=row(id).leaseUntil();assertTrue(Duration.between(deliveries.now(),lease).toSeconds()>=28);
        assertNull(tx.execute(s->deliveries.claim()));jdbc.update("UPDATE m2.g5_operation SET lease_until=clock_timestamp()-interval '1 second' WHERE id=?",id);var second=tx.execute(s->deliveries.claim());assertNotEquals(first.token(),second.token());assertEquals(2,second.sequence());assertFalse(Boolean.TRUE.equals(tx.execute(s->deliveries.finish(first,null,false,false,OffsetDateTime.now()))));assertEquals("RUNNING",row(id).status());assertTrue(Boolean.TRUE.equals(tx.execute(s->deliveries.finish(second,null,false,true,OffsetDateTime.now()))));assertEquals("SUCCEEDED",row(id).status());assertEquals(List.of("UNKNOWN","ACCEPTED"),jdbc.queryForList("SELECT outcome FROM m2.g5_outbox_attempt WHERE operation_id=? ORDER BY sequence",String.class,id));
    }
    @Test @Order(6) void transientFailurePersistsBackoffThenRecoversWithoutChangingEvent()throws Exception{
        quiet();UUID id=append();mode.set("FAIL");worker();await(id,"RETRY_WAIT",10);var failure=row(id);assertEquals(1,failure.attempts());long gap=Duration.between(deliveries.now(),failure.nextRetryAt()).getSeconds();assertTrue(gap>=3&&gap<=5);assertEquals(1,hits(id));mode.set("OK");await(id,"SUCCEEDED",10);assertEquals(2,row(id).attempts());assertNotNull(references.get(principal(),id).acceptedAt());
    }
    @Test @Order(7) void invalidReceiptsAndRealTimeoutNeverAcknowledgeDelivery()throws Exception{
        for(String value:List.of("WRONG","BADJSON","SLOW")){quiet();mode.set(value);UUID id=append();worker();await(id,"RETRY_WAIT",12);stopWorkers();assertNull(row(id).deliveredAt());assertEquals("PENDING",scalar("SELECT status FROM m2.g5_outbox WHERE event_id='"+id+"'"));assertNotNull(row(id).errorCode());}
    }
    @Test @Order(8) void droppedResponseRetriesOriginalIdAndReceiverReportsDuplicate()throws Exception{
        quiet();UUID id=append();mode.set("DROP");worker();await(id,"RETRY_WAIT",10);assertTrue(accepted.containsKey(id));mode.set("OK");await(id,"SUCCEEDED",10);assertTrue(row(id).duplicate());assertEquals("ALREADY_APPLIED",operations.get(principal(),id).result().outcome());assertEquals(2,hits(id));
    }
    @Test @Order(9) void rejectionBlocksAndOnlyAuthorizedRequeueCanResume()throws Exception{
        quiet();UUID id=append();mode.set("REJECT");worker();await(id,"BLOCKED",10);assertTrue(row(id).attention());assertNull(row(id).nextRetryAt());Thread.sleep(1200);assertEquals(1,hits(id));stopWorkers();mode.set("OK");var pending=operations.retry(principal(),id,Map.of("expectedVersion",Long.toString(row(id).version()),"reason","提供方契约已修复"),UUID.randomUUID());assertTrue(pending.needsAttention());worker();await(id,"SUCCEEDED",10);assertFalse(row(id).attention());
    }
    @Test @Order(10) void missingConfigurationIsVisibleAndOldFailuresKeepAttentionAndAge()throws Exception{
        quiet();UUID id=append();env.setProperty("G6_BASE_URL","http://127.0.0.1:1/invalid/path");try{worker();await(id,"BLOCKED",8);assertEquals(0,hits(id));assertEquals("DELIVERY_NOT_CONFIGURED",row(id).errorCode());assertTrue(operations.get(principal(),id).error().message().contains("配置"));}finally{stopWorkers();env.setProperty("G6_BASE_URL","http://127.0.0.1:"+g6.getAddress().getPort());}
        quiet();id=append();final UUID old=id;jdbc.update("UPDATE m2.g5_operation SET accepted_at=clock_timestamp()-interval '6 minutes' WHERE id=?",id);tx.executeWithoutResult(s->deliveries.overdue());assertTrue(row(id).attention());state(id,"BLOCKED");String before=operations.get(principal(),id).acceptedAt();operations.retry(principal(),id,Map.of("expectedVersion",Long.toString(row(id).version()),"reason","继续核验原事件"),UUID.randomUUID());assertEquals(before,operations.get(principal(),old).acceptedAt());assertTrue(row(id).attention());
    }
    @Test @Order(11) void concurrentDispatchersUseOneClaimAndHoldNoDatabaseLockDuringHttp()throws Exception{
        quiet();UUID id=append();mode.set("NOLOCK");worker();worker();await(id,"SUCCEEDED",10);assertEquals(1,hits(id));assertEquals(1,row(id).attempts());assertEquals(1,count("SELECT count(*) FROM m2.g5_outbox_attempt WHERE operation_id='"+id+"'"));
    }
    @Test @Order(12) void realProcessCrashLeavesLeaseThenNewProcessReclaimsOriginalAcceptedEvent()throws Exception{
        quiet();UUID id=append();holdId.set(id);held=new CountDownLatch(1);release=new CountDownLatch(1);stopBackend();startBackend(true);assertTrue(held.await(10,TimeUnit.SECONDS));assertEquals("RUNNING",row(id).status());assertTrue(accepted.containsKey(id));
        process.destroyForcibly();assertTrue(process.waitFor(10,TimeUnit.SECONDS));release.countDown();startBackend(true);await(id,"SUCCEEDED",40);assertTrue(row(id).duplicate());assertEquals(2,row(id).attempts());assertEquals(2,hits(id));assertEquals(List.of("UNKNOWN","ACCEPTED"),jdbc.queryForList("SELECT outcome FROM m2.g5_outbox_attempt WHERE operation_id=? ORDER BY sequence",String.class,id));stopBackend();startBackend(false);
    }
    @Test @Order(13) void operationStoreFailureRollsBackConfigurationAndDispatcherRecoversDatabaseFault()throws Exception{
        quiet();var before=request("GET","/api/m2/admin/config",null,admin,null);Map<String,Object> config=JsonPath.read(before.body(),"$.data");Map<String,Object> values=new TreeMap<>((Map<String,Object>)config.get("values"));values.put("reservationMaxHours",7);long events=count("SELECT count(*) FROM m2.g5_audit_event");
        sql("CREATE FUNCTION m2.fail_delivery_operation() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'isolated delivery operation failure'; END $$");sql("CREATE TRIGGER fail_delivery_operation BEFORE INSERT ON m2.g5_operation FOR EACH ROW EXECUTE FUNCTION m2.fail_delivery_operation()");
        try{var result=request("PATCH","/api/m2/admin/config",Map.of("expectedVersion",config.get("version"),"values",values),admin,Map.of("Idempotency-Key",key()));assertEquals(503,result.statusCode());assertEquals(config.get("version"),scalar("SELECT version::text FROM m2.business_config"));assertEquals(events,count("SELECT count(*) FROM m2.g5_audit_event"));}
        finally{sql("DROP TRIGGER fail_delivery_operation ON m2.g5_operation");sql("DROP FUNCTION m2.fail_delivery_operation()");}
        UUID id=append();sql("ALTER TABLE m2.g5_operation RENAME TO g5_operation_unavailable");worker();Thread.sleep(1500);try{code("DEPENDENCY_UNAVAILABLE",()->operations.metrics(principal(),null));assertEquals(0,hits(id));}finally{sql("ALTER TABLE m2.g5_operation_unavailable RENAME TO g5_operation");}await(id,"SUCCEEDED",10);
    }

    @Test @Order(14) void scanConfigurationAndPersistentRetryScheduleFollowCurrentParameters()throws Exception{
        quiet();UUID id=append();int[] expected={5,15,30,60,60};for(int wait:expected){var claim=tx.execute(s->deliveries.claim());assertEquals(id,claim.id());tx.executeWithoutResult(s->deliveries.finish(claim,"DEPENDENCY_UNAVAILABLE",false,null,null));long seconds=Duration.between(deliveries.now(),row(id).nextRetryAt()).getSeconds();assertTrue(seconds>=wait-1&&seconds<=wait);jdbc.update("UPDATE m2.g5_operation SET next_retry_at=clock_timestamp() WHERE id=?",id);}
        quiet();jdbc.update("UPDATE m2.business_config SET scan_interval_seconds=2 WHERE id=1");UUID first=append();worker();await(first,"SUCCEEDED",8);jdbc.update("UPDATE m2.business_config SET scan_interval_seconds=1 WHERE id=1");UUID second=append();await(second,"SUCCEEDED",5);assertEquals(1,configuration.current().values().get("scanIntervalSeconds"));
    }
    @Test @Order(15) void grantRevocationRemovesSystemListMetricsAndRecoveryEvenWithOldPrincipal()throws Exception{
        quiet();UUID id=append();state(id,"BLOCKED");Principal actor=principal();String role=scalar("SELECT g.role_id::text FROM m2.role_binding b JOIN m2.role_grant g ON b.role_id=g.role_id WHERE b.account_id='"+adminId+"' AND g.action='operation:read' AND g.scope_kind='SYSTEM' LIMIT 1");
        jdbc.update("DELETE FROM m2.role_grant WHERE role_id=? AND action='operation:read'",UUID.fromString(role));try{code("FORBIDDEN",()->operations.list(actor,Map.of()));code("FORBIDDEN",()->operations.metrics(actor,null));code("NOT_FOUND",()->references.get(actor,id));code("FORBIDDEN",()->operations.retry(actor,id,Map.of("expectedVersion",Long.toString(row(id).version()),"reason","撤权后不能重试"),UUID.randomUUID()));}
        finally{jdbc.update("INSERT INTO m2.role_grant(id,role_id,action,scope_kind) VALUES (?,?,'operation:read','SYSTEM')",UUID.randomUUID(),UUID.fromString(role));}
        var expired=new Principal(actor.userId(),actor.displayName(),actor.deptId(),actor.accountVersion(),actor.permissionVersion(),actor.grants(),false,OffsetDateTime.now().minusSeconds(1).toString());code("UNAUTHENTICATED",()->operations.get(expired,id));
    }
    private Session login(String code,String password) throws Exception {var r=request("POST","/api/m2/auth/login",Map.of("account",code,"password",password),null,null);assertEquals(200,r.statusCode(),r.body());String cookie=r.headers().firstValue("Set-Cookie").orElseThrow();return new Session(cookie.substring(11,cookie.indexOf(';')),JsonPath.read(r.body(),"$.data.csrfToken"));}
    private HttpResponse<String> request(String method,String path,Object body,Session session,Map<String,String> headers) throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(15));
        if(session!=null){request.header("Cookie","PJ_SESSION="+session.token());if(session.csrf()!=null)request.header("X-CSRF-Token",session.csrf());}if(headers!=null)headers.forEach(request::header);
        if(body!=null)request.header("Content-Type","application/json");request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        return http.send(request.build(),HttpResponse.BodyHandlers.ofString());
    }
    private void assertCode(HttpResponse<String> r,int status,String code){assertEquals(status,r.statusCode(),"Expected "+code);assertEquals(code,JsonPath.read(r.body(),"$.code"));}
    private void startBackend(boolean worker) throws Exception {
        port=freePort();do{managementPort=freePort();}while(managementPort==port);Path jar=Path.of(System.getProperty("appJar")).toAbsolutePath();
        var command=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString(),"-jar",jar.toString());
        command.environment().putAll(Map.of("DB_URL",database,"DB_MIGRATION_URL",database,"APP_BIND_ADDRESS","127.0.0.1","APP_PORT",Integer.toString(port),"MANAGEMENT_PORT",Integer.toString(managementPort),"G5_BOOTSTRAP_ENABLED","true","G5_BOOTSTRAP_ACCOUNT","outbox-manager","G5_BOOTSTRAP_PASSWORD",password,"G5_IDEMPOTENCY_HMAC_KEY",hmac));
        serviceTokens.forEach((group,token)->command.environment().put("G5_SERVICE_"+group+"_TOKEN",token));command.environment().put("G5_OUTBOX_ENABLED",Boolean.toString(worker));command.environment().put("G6_BASE_URL","http://127.0.0.1:"+g6.getAddress().getPort());command.environment().put("G6_SERVICE_G5_TOKEN",g6Token);command.redirectErrorStream(true).redirectOutput(jar.getParent().resolve("outbox-it-"+port+".log").toFile());process=command.start();
        long deadline=System.nanoTime()+Duration.ofSeconds(90).toNanos();while(System.nanoTime()<deadline){assertTrue(process.isAlive(),"Configuration backend exited; inspect ignored log");try{if(http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+managementPort+"/actuator/health/readiness")).GET().build(),HttpResponse.BodyHandlers.ofString()).statusCode()==200)return;}catch(IOException ignored){}Thread.sleep(200);}fail("Config backend readiness timeout");
    }
    private void stopBackend() throws Exception {if(process!=null&&process.isAlive()){process.destroy();if(!process.waitFor(15,TimeUnit.SECONDS)){process.destroyForcibly();assertTrue(process.waitFor(10,TimeUnit.SECONDS));}}}
    private int freePort() throws IOException {try(var socket=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))){return socket.getLocalPort();}}
    private void sql(String query) throws Exception {try(var connection=DriverManager.getConnection(database,System.getenv("DB_MIGRATION_USERNAME"),System.getenv("DB_MIGRATION_PASSWORD"));var statement=connection.createStatement()){statement.execute(query);}}
    private String scalar(String query) throws Exception {try(var connection=DriverManager.getConnection(database,System.getenv("DB_MIGRATION_USERNAME"),System.getenv("DB_MIGRATION_PASSWORD"));var statement=connection.createStatement();var rows=statement.executeQuery(query)){assertTrue(rows.next());return rows.getString(1);}}
    private long count(String query) throws Exception {return Long.parseLong(scalar(query));}
}
