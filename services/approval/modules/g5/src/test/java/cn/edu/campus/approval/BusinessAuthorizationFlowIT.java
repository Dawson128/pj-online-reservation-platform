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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.mock.env.MockEnvironment;
import cn.edu.campus.approval.g5.api.*;
import cn.edu.campus.approval.g5.application.*;
import cn.edu.campus.approval.g5.domain.*;
import cn.edu.campus.approval.g5.persistence.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real G5 packaged HTTP/Postgres; only G4 is a controlled HTTP boundary. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BusinessAuthorizationFlowIT {
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final JsonMapper json=new JsonMapper();
    private final String password=UUID.randomUUID().toString().replace("-","").substring(0,24),hmac=key()+key(),g4Token=key()+key();
    private final Map<String,String> serviceTokens=new LinkedHashMap<>();
    private final String owner=uid(101),manager=uid(102),repairer=uid(103),operator=uid(104),outsider=uid(105),noemail=uid(106),disabled=uid(107),deptA=uid(301),deptB=uid(302),roomA=uid(201),roomB=uid(202),school=uid(203);
    private final AtomicReference<String> mode=new AtomicReference<>("OK");
    private final AtomicReference<Runnable> duringRpc=new AtomicReference<>();
    private final AtomicInteger calls=new AtomicInteger();
    private final List<String> boundaryErrors=new CopyOnWriteArrayList<>();
    private HttpServer g4;private ExecutorService executor;
    private String database,adminId;private Process process;private int port,managementPort;private Session admin,assigner,student,repairSession;
    private record Session(String token,String csrf) { @Override public String toString(){return "Session[REDACTED]";} }
    private static String uid(int n){return "00000000-0000-0000-0000-"+String.format("%012d",n);}
    private static String key(){return UUID.randomUUID().toString();}
    @BeforeAll void start() throws Exception {
        database=System.getenv("G5_BUSINESS_TEST_DB_URL");assertTrue(database!=null&&database.matches("jdbc:postgresql://[^/]+/pj_g5_auth_it_business_[a-zA-Z0-9_]+"));
        for(String variable:List.of("DB_URL","G5_MANAGEMENT_TEST_DB_URL","G5_CONFIG_TEST_DB_URL","G5_RESOURCE_FACTS_TEST_DB_URL"))assertNotEquals(database,System.getenv(variable));
        for(String group:List.of("G1","G2","G3","G4","G6"))serviceTokens.put(group,key()+key());startG4();startBackend();admin=login("business-manager",password);adminId=text(request("GET","/api/m2/auth/me",null,admin,null),"userId");
        sql("INSERT INTO m2.department(id,code,name) VALUES ('"+deptA+"','BUS_A','A'),('"+deptB+"','BUS_B','B')");
        for(String id:List.of(owner,manager,repairer,operator,outsider,noemail,disabled)){
            sql("INSERT INTO m2.account(id,user_code,display_name,department_id,email,password_hash,enabled) SELECT '"+id+"','business-"+id.substring(30)+"','合成验证账户', '"+(id.equals(repairer)||id.equals(outsider)?deptB:deptA)+"','"+(id.equals(noemail)?"": "shared@example.invalid")+"',password_hash,"+!id.equals(disabled)+" FROM m2.account WHERE id='"+adminId+"'");
            sql("INSERT INTO m2.role(id,key,name) VALUES ('"+id+"','BUS_"+id.substring(30)+"','BUS_"+id.substring(30)+"')");sql("INSERT INTO m2.role_binding(id,account_id,role_id) VALUES (gen_random_uuid(),'"+id+"','"+id+"')");
        }
        for(String action:List.of("room:read","reservation:create","reservation:read-own","room:apply","workorder:report"))grant(owner,action,"SCHOOL",null);
        for(String action:List.of("workorder:assign","workorder:read","workorder:accept","approval:level1","approval:level2","operation:recover"))grant(manager,action,"ROOM",roomA);
        grant(repairer,"workorder:repair","ROOM",roomA);grant(outsider,"workorder:repair","ROOM",roomB);
        grant(operator,"workorder:escalation-receive","DEPARTMENT",deptA);grant(operator,"workorder:read","ROOM",roomA);grant(operator,"operation:recover","SYSTEM",null);
        assigner=login("business-000102",password);student=login("business-000101",password);repairSession=login("business-000103",password);
    }
    @AfterAll void stop() throws Exception {stopBackend();if(g4!=null)g4.stop(0);if(executor!=null)executor.shutdownNow();assertEquals(List.of(),boundaryErrors);}
    private void grant(String user,String action,String scope,String scopeId)throws Exception{sql("INSERT INTO m2.role_grant(id,role_id,action,scope_kind,department_id,room_id) VALUES (gen_random_uuid(),'"+user+"','"+action+"','"+scope+"',"+(scope.equals("DEPARTMENT")?"'"+scopeId+"'":"NULL")+","+(scope.equals("ROOM")?"'"+scopeId+"'":"NULL")+")");}
    private Map<String,String> service(String group){return Map.of("Authorization","Bearer "+serviceTokens.get(group));}
    private Map<String,String> actorHeaders(String group,Session session){var headers=new HashMap<>(service(group));headers.put("X-Actor-Session",session.token());return headers;}
    private Map<String,Object> check(String action,String room){return Map.of("checkId",key(),"action",action,"target",room==null?Map.of("kind","SYSTEM"):Map.of("kind","ROOM","roomId",room));}
    private Map<String,Object> batch(String actor,List<Map<String,Object>> checks){return Map.of("actorUserId",actor,"accountVersion","1","permissionVersion","1","checks",checks);}
    private HttpResponse<String> authorize(String actor,Session session,List<Map<String,Object>> checks)throws Exception{return request("POST","/internal/authorization/check",batch(actor,checks),null,actorHeaders("G1",session));}
    private Map<String,Object> qualification(String subject,boolean repair){return Map.of("subjectId",subject,"roomId",roomA,"action",repair?"workorder:repair":"room:apply","source",Map.of("type",repair?"REPAIR":"WHOLE","id",key()),"operationId",key(),"purpose",repair?"REPAIR_START":"FINAL_APPROVAL");}
    private HttpResponse<String> eligible(String subject,boolean repair)throws Exception{return request("POST","/internal/authorization/eligibility",qualification(subject,repair),null,service(repair?"G3":"G4"));}
    private List<Map<String,Object>> items(HttpResponse<String> r){assertEquals(200,r.statusCode(),r.body());return JsonPath.read(r.body(),"$.data.items");}
    private Map<String,Object> data(HttpResponse<String> r){assertEquals(200,r.statusCode(),r.body());return JsonPath.read(r.body(),"$.data");}
    private String text(HttpResponse<String> r,String field){return (String)data(r).get(field);}
    private void assertCode(HttpResponse<String> r,int status,String code){assertEquals(status,r.statusCode(),r.body());assertEquals(code,JsonPath.read(r.body(),"$.code"));assertFalse(json.readTree(r.body()).has("data"));}
    private List<String> recipientIds(HttpResponse<String> r){return ((List<Map<String,Object>>)data(r).get("recipients")).stream().map(i->(String)i.get("userId")).toList();}
    private Map<String,Object> event(String type)throws Exception{
        Path examples=Path.of(System.getProperty("appJar")).getParent().resolve("../../../contracts/events/g5/examples/notification-events.json").normalize();
        List<Map<String,Object>> all=json.readValue(Files.readString(examples),List.class);return all.stream().filter(e->type.equals(e.get("eventType"))).findFirst().orElseThrow();
    }
    private HttpResponse<String> route(Map<String,Object> event)throws Exception{return request("POST","/internal/notification-recipients/query",Map.of("purpose","NOTIFICATION_ROUTING","event",event),null,service("G6"));}
    private Map<String,Object> payload(Map<String,Object> event){return (Map<String,Object>)event.get("payload");}
    private void startG4() throws Exception{
        g4=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);executor=Executors.newCachedThreadPool();g4.setExecutor(executor);
        g4.createContext("/internal/rooms/",exchange->{
            try {
                calls.incrementAndGet();if(!("Bearer "+g4Token).equals(exchange.getRequestHeaders().getFirst("Authorization")))throw new AssertionError("G4 service identity missing");
                if(exchange.getRequestHeaders().containsKey("X-Actor-Session"))throw new AssertionError("Actor session leaked");
                Runnable callback=duringRpc.getAndSet(null);if(callback!=null)callback.run();String state=mode.get();
                if(state.equals("DELAY"))Thread.sleep(5500);if(state.equals("FAIL")){exchange.sendResponseHeaders(503,-1);return;}
                Object result;String path=exchange.getRequestURI().getPath();
                if(path.endsWith("/query")){
                    if(!exchange.getRequestMethod().equals("POST"))throw new AssertionError("batch method");var body=json.readValue(exchange.getRequestBody(),Map.class);
                    if(!body.keySet().equals(Set.of("purpose","roomIds","traceId"))||!body.get("purpose").equals("AUTHORIZATION"))throw new AssertionError("batch shape");
                    var roomIds=(List<String>)body.get("roomIds");if(new HashSet<>(roomIds).size()!=roomIds.size())throw new AssertionError("G5 did not dedup room IDs");var list=new ArrayList<Map<String,Object>>();
                    for(String room:roomIds){var item=new LinkedHashMap<String,Object>();item.put("roomId",room);item.put("facts",facts(room));list.add(item);}
                    if(state.equals("MISSING"))list.removeLast();if(state.equals("DUPLICATE")&&list.size()>1)list.set(1,list.getFirst());result=Map.of("items",list,"asOf",OffsetDateTime.now().toString());
                }else{
                    String room=path.split("/")[3];String query=exchange.getRequestURI().getQuery();if(!query.contains("traceId="))throw new AssertionError("trace missing");
                    if(query.contains("NOTIFICATION_ROUTING")&&(!query.contains("eventType=")||!query.contains("eventId=")))throw new AssertionError("notification purpose incomplete");
                    result=facts(room);if(result==null){exchange.sendResponseHeaders(404,-1);return;}
                }
                if(state.equals("MALFORMED"))result=Map.of("roomId",roomA);byte[] bytes=json.writeValueAsBytes(Map.of("data",result,"traceId",key()));exchange.getResponseHeaders().add("Content-Type","application/json");exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);
            }catch(InterruptedException ignored){Thread.currentThread().interrupt();}catch(IOException closed){/* timeout deliberately closes the client */}catch(Throwable bad){boundaryErrors.add(bad.toString());exchange.sendResponseHeaders(500,-1);}finally{exchange.close();}
        });g4.start();
    }
    private Map<String,Object> facts(String room){if(!List.of(roomA,roomB,school).contains(room))return null;Map<String,Object> f=new LinkedHashMap<>();f.put("roomId",room);f.put("schoolWide",room.equals(school));f.put("deptId",room.equals(school)?null:room.equals(roomA)&&!mode.get().equals("DEPT_B")?deptA:deptB);f.put("enabled",!mode.get().equals("DISABLED"));f.put("roomVersion","3");f.put("asOf",OffsetDateTime.now().toString());return f;}

    @Test @Order(1) void callerMatrixAndStrictBodiesFailBeforeDataAccess()throws Exception{
        for(String path:List.of("/internal/authorization/check","/internal/authorization/eligibility","/internal/maintenance/assignee-candidates","/internal/maintenance/assignee-eligibility","/internal/notification-recipients/query","/internal/notification-recipients/validate"))assertCode(request("POST",path,Map.of("bad",true),admin,null),403,"FORBIDDEN");
        for(String group:List.of("G1","G2","G4","G6"))assertCode(request("POST","/internal/maintenance/assignee-candidates",Map.of(),null,service(group)),403,"FORBIDDEN");
        for(String group:List.of("G1","G2","G3","G4"))assertCode(request("POST","/internal/notification-recipients/query",Map.of(),null,service(group)),403,"FORBIDDEN");
        assertCode(request("POST","/internal/authorization/check",batch(owner,List.of(check("room:read",roomA))),null,service("G1")),401,"UNAUTHENTICATED");
        assertCode(authorize(manager,student,List.of(check("workorder:assign",roomA))),403,"FORBIDDEN");
        var stale=new HashMap<>(batch(owner,List.of(check("room:read",roomA))));stale.put("permissionVersion","2");assertCode(request("POST","/internal/authorization/check",stale,null,actorHeaders("G1",student)),409,"VERSION_CONFLICT");
        var duplicate=check("room:read",roomA);assertCode(authorize(owner,student,List.of(duplicate,duplicate)),400,"VALIDATION_ERROR");
        assertCode(authorize(owner,student,List.of(check("madeup:all",roomA))),400,"VALIDATION_ERROR");
        assertCode(authorize(owner,student,Collections.nCopies(101,duplicate)),400,"VALIDATION_ERROR");
        var extra=new HashMap<>(qualification(owner,false));extra.put("extra",1);assertCode(request("POST","/internal/authorization/eligibility",extra,null,service("G4")),400,"VALIDATION_ERROR");
        assertCode(request("POST","/internal/authorization/eligibility",qualification(owner,false),null,service("G3")),400,"VALIDATION_ERROR");
        assertCode(request("POST","/%69nternal/notification-recipients/query",Map.of(),null,service("G3")),403,"FORBIDDEN");
    }
    @Test @Order(2) void systemAdministratorHasNoImplicitBusinessPowersAndSystemChecksNeverCallG4()throws Exception{
        int before=calls.get();var result=items(authorize(adminId,admin,List.of(check("system:manage",null),check("reservation:create",null))));
        assertEquals(List.of(true,false),result.stream().map(i->i.get("allowed")).toList());assertEquals(before,calls.get());
        var roomResult=items(authorize(adminId,admin,List.of(check("reservation:create",roomA),check("system:manage",roomA))));assertTrue(roomResult.stream().noneMatch(i->Boolean.TRUE.equals(i.get("allowed"))));
    }
    @Test @Order(3) void roomFactsAreDeduplicatedAndAccessLimitsNewActionsButNotHistoricalScopes()throws Exception{
        var r=authorize(owner,student,List.of(check("reservation:create",roomA),check("reservation:create",roomB),check("reservation:create",school),check("reservation:read-own",roomB),check("room:read",uid(299))));
        assertEquals(List.of(true,false,true,true,false),items(r).stream().map(i->i.get("allowed")).toList());assertEquals("RESOURCE_NOT_FOUND",items(r).getLast().get("reason"));
        var data=data(r);assertEquals(Duration.ofSeconds(5),Duration.between(OffsetDateTime.parse((String)data.get("checkedAt")),OffsetDateTime.parse((String)data.get("validUntil"))));
        mode.set("DISABLED");try{assertEquals(true,items(authorize(manager,assigner,List.of(check("workorder:assign",roomA)))).getFirst().get("allowed"));}finally{mode.set("OK");}
        // SCHOOL/ROOM/DEPARTMENT are evaluated from grants, never account role labels.
        grant(manager,"resource:trace","DEPARTMENT",deptA);var scoped=items(authorize(manager,assigner,List.of(check("resource:trace",roomA),check("resource:trace",school),check("resource:trace",roomB))));assertEquals(List.of(true,false,false),scoped.stream().map(i->i.get("allowed")).toList());
    }
    @Test @Order(4) void batchCompletenessMalformedFactsTimeoutAndRevocationFailClosed()throws Exception{
        for(String state:List.of("MISSING","DUPLICATE","MALFORMED","FAIL")){
            mode.set(state);try{assertCode(authorize(owner,student,List.of(check("room:read",roomA),check("room:read",roomB))),503,"SCOPE_FACTS_UNAVAILABLE");}finally{mode.set("OK");}
        }
        mode.set("DELAY");Instant before=Instant.now();try{assertCode(authorize(owner,student,List.of(check("room:read",roomA))),503,"SCOPE_FACTS_UNAVAILABLE");assertTrue(Duration.between(before,Instant.now()).toSeconds()<8);}finally{mode.set("OK");}
        duringRpc.set(()->{try{sql("UPDATE m2.account SET permission_version=permission_version+1 WHERE id='"+owner+"'");}catch(Exception bad){throw new RuntimeException(bad);}});
        assertCode(authorize(owner,student,List.of(check("room:read",roomA))),401,"UNAUTHENTICATED");sql("UPDATE m2.account SET permission_version=1 WHERE id='"+owner+"'");student=login("business-000101",password);
    }
    @Test @Order(5) void finalApprovalAndRepairQualificationUseIndependentCurrentSubjectFacts()throws Exception{
        assertEquals(true,data(eligible(owner,false)).get("eligible"));var repair=data(eligible(repairer,true));assertEquals(true,repair.get("eligible"));assertEquals(false,repair.get("canAccessRoom"));assertEquals(true,repair.get("scopeMatched"));
        assertEquals(false,data(eligible(adminId,false)).get("hasPermission"));assertEquals(false,data(eligible(outsider,true)).get("eligible"));
        grant(outsider,"room:apply","SCHOOL",null);var finalDecision=data(eligible(outsider,false));assertEquals(true,finalDecision.get("scopeMatched"));assertEquals(false,finalDecision.get("eligible"));
        assertCode(eligible(uid(777),true),404,"NOT_FOUND");sql("UPDATE m2.account SET enabled=false,version=version+1 WHERE id='"+repairer+"'");try{var inactive=data(eligible(repairer,true));assertEquals(false,inactive.get("eligible"));assertEquals(false,inactive.get("active"));}finally{sql("UPDATE m2.account SET enabled=true,version=1 WHERE id='"+repairer+"'");}
    }
    @Test @Order(6) void candidateFiltersBeforePaginationAndTargetQualificationIsNotActorQualification()throws Exception{
        var body=Map.of("actorUserId",manager,"roomId",roomA);var response=request("POST","/internal/maintenance/assignee-candidates",body,null,actorHeaders("G3",assigner));assertEquals(1,data(response).get("total"));assertEquals(repairer,items(response).getFirst().get("userId"));assertEquals(Set.of("userId","displayName","qualificationSummary"),items(response).getFirst().keySet());
        var target=Map.of("actorUserId",manager,"roomId",roomA,"assigneeId",repairer);assertEquals(true,data(request("POST","/internal/maintenance/assignee-eligibility",target,null,actorHeaders("G3",assigner))).get("eligible"));
        assertCode(request("POST","/internal/maintenance/assignee-eligibility",Map.of("actorUserId",owner,"roomId",roomA,"assigneeId",uid(777)),null,actorHeaders("G3",student)),403,"FORBIDDEN");
        sql("INSERT INTO m2.account(id,user_code,display_name,department_id,email,password_hash) SELECT gen_random_uuid(),'business-bulk-'||n,'批量维修夹具','"+deptB+"','bulk@example.invalid',password_hash FROM generate_series(1,105)n CROSS JOIN m2.account WHERE id='"+adminId+"'");
        sql("INSERT INTO m2.role_binding(id,account_id,role_id) SELECT gen_random_uuid(),id,'"+repairer+"' FROM m2.account WHERE user_code LIKE 'business-bulk-%'");
        try{var page=request("POST","/internal/maintenance/assignee-candidates",Map.of("actorUserId",manager,"roomId",roomA,"keyword","business-bulk-","page",2,"pageSize",100),null,actorHeaders("G3",assigner));assertEquals(105,data(page).get("total"));assertEquals(5,items(page).size());assertFalse(page.body().contains("email"));}
        finally{sql("UPDATE m2.account SET enabled=false WHERE user_code LIKE 'business-bulk-%'");}
    }
    @Test @Order(7) void allNineteenEventsResolveThroughActualSchemaAndPolicies()throws Exception{
        Map<String,List<String>> expected=new LinkedHashMap<>();
        for(String name:List.of("ReservationReminderEarly","ReservationReminderLate","ReservationCancelled","AppealDecided","ViolationCorrected","ApprovalStatusChanged","WorkOrderRepairStarted","WorkOrderAccepted"))expected.put(name,List.of(owner));
        expected.put("ApprovalTaskOpened",List.of(manager));expected.put("WorkOrderAssigned",List.of(manager,repairer));expected.put("WorkOrderCompletionSubmitted",List.of(owner,manager));expected.put("WorkOrderEscalated",List.of(manager,repairer,operator));expected.put("WorkOrderReturned",List.of(owner,repairer));expected.put("WorkOrderClosed",List.of(owner,repairer));expected.put("RecoveryAttention",List.of(manager));
        for(String name:List.of("ClaimInvalidated","MaintenanceImpactApplied","ConfigChanged","AuditRecorded"))expected.put(name,List.of());
        assertEquals(19,expected.size());for(var entry:expected.entrySet()){var response=route(event(entry.getKey()));assertEquals(new HashSet<>(entry.getValue()),new HashSet<>(recipientIds(response)),entry.getKey());assertEquals(entry.getValue().isEmpty()?"NO_ELIGIBLE_RECIPIENTS":"RESOLVED",data(response).get("outcome"));}
    }
    @Test @Order(8) void completeEventSchemaAndEnvelopeLinksRejectInvalidRoutingInputs()throws Exception{
        for(String name:List.of("ReservationReminderEarly","ApprovalTaskOpened","WorkOrderAssigned","WorkOrderCompletionSubmitted","WorkOrderAccepted","RecoveryAttention","ConfigChanged","AuditRecorded")){
            var missing=event(name);payload(missing).put("freeEmail","bad@example.invalid");assertCode(route(missing),400,"VALIDATION_ERROR");
            var producer=event(name);producer.put("producer","G9");assertCode(route(producer),400,"VALIDATION_ERROR");
            var mismatch=event(name);mismatch.put("eventId",key());assertCode(route(mismatch),400,"VALIDATION_ERROR");
        }
        var invalid=event("ApprovalTaskOpened");payload(invalid).put("excludedActorIds",List.of(repairer));assertCode(route(invalid),400,"VALIDATION_ERROR");
        invalid=event("ApprovalTaskOpened");payload(invalid).put("level",2);assertCode(route(invalid),400,"VALIDATION_ERROR");
        invalid=event("ReservationReminderEarly");payload(invalid).put("start","not-time");assertCode(route(invalid),400,"VALIDATION_ERROR");
        invalid=event("ApprovalStatusChanged");payload(invalid).put("status","ACQUIRING");assertCode(route(invalid),400,"VALIDATION_ERROR");
        invalid=event("WorkOrderCompletionSubmitted");payload(invalid).put("actualRepairerIds",Collections.nCopies(101,repairer));assertCode(route(invalid),400,"VALIDATION_ERROR");
    }
    @Test @Order(9) void dedupIsByUserAndBranchesPreserveParticipantsDuringManagerExclusion()throws Exception{
        var assigned=event("WorkOrderAssigned");payload(assigned).put("oldAssigneeId",repairer);var result=(List<Map<String,Object>>)data(route(assigned)).get("recipients");assertEquals(1,result.size());assertEquals(Set.of("OLD_ASSIGNEE","NEW_ASSIGNEE"),new HashSet<>((List<String>)result.getFirst().get("branches")));
        var completion=event("WorkOrderCompletionSubmitted");payload(completion).put("reporterId",manager);payload(completion).put("actualRepairerIds",List.of(manager));result=(List<Map<String,Object>>)data(route(completion)).get("recipients");assertEquals(1,result.size());assertEquals(List.of("REPORTER"),result.getFirst().get("branches"));
        // owner and manager share an email but remain two recipients.
        assertEquals(2,recipientIds(route(event("WorkOrderCompletionSubmitted"))).size());
        var level2=event("ApprovalTaskOpened");payload(level2).put("level",2);payload(level2).put("excludedActorIds",List.of(owner,manager));assertEquals(List.of(),recipientIds(route(level2)));
    }
    @Test @Order(10) void exclusionsAndBeforeSendValidationRecomputeFullCurrentPolicy()throws Exception{
        var returned=event("WorkOrderReturned");payload(returned).put("reporterId",disabled);payload(returned).put("assigneeId",noemail);var result=data(route(returned));assertEquals("NO_ELIGIBLE_RECIPIENTS",result.get("outcome"));var excluded=(List<Map<String,Object>>)result.get("excluded");assertEquals(Set.of("ACCOUNT_DISABLED","NO_EMAIL"),new HashSet<>(excluded.stream().map(i->(String)i.get("reason")).toList()));
        var completion=event("WorkOrderCompletionSubmitted");var before=request("POST","/internal/notification-recipients/validate",Map.of("event",completion,"recipientId",manager),null,service("G6"));assertEquals(true,data(before).get("eligible"));
        sql("DELETE FROM m2.role_grant WHERE role_id='"+manager+"' AND action='workorder:accept'");try{
            var after=request("POST","/internal/notification-recipients/validate",Map.of("event",completion,"recipientId",manager),null,service("G6"));assertEquals(false,data(after).get("eligible"));assertNull(data(after).get("recipient"));assertEquals(List.of(owner),recipientIds(route(completion)));
        }finally{grant(manager,"workorder:accept","ROOM",roomA);}
        var recovery=event("RecoveryAttention");recovery.put("roomId",null);var audit=(Map<String,Object>)recovery.get("audit");audit.put("roomId",null);audit.put("scopeKind","SYSTEM");int beforeCalls=calls.get();assertEquals(Set.of(operator,adminId),new HashSet<>(recipientIds(route(recovery))));assertEquals(beforeCalls,calls.get());
    }
    @Test @Order(11) void mandatoryDependenciesNeverYieldPartialRecipientsAndLimitNeverTruncates()throws Exception{
        mode.set("FAIL");try{assertCode(route(event("WorkOrderCompletionSubmitted")),503,"SCOPE_FACTS_UNAVAILABLE");assertCode(request("POST","/internal/notification-recipients/validate",Map.of("event",event("WorkOrderReturned"),"recipientId",owner),null,service("G6")),503,"SCOPE_FACTS_UNAVAILABLE");}finally{mode.set("OK");}
        var unknown=event("WorkOrderReturned");payload(unknown).put("reporterId",uid(777));assertCode(route(unknown),503,"DEPENDENCY_UNAVAILABLE");
        sql("ALTER TABLE m2.role_grant RENAME TO role_grant_unavailable");try{assertCode(route(event("WorkOrderReturned")),503,"DEPENDENCY_UNAVAILABLE");}finally{sql("ALTER TABLE m2.role_grant_unavailable RENAME TO role_grant");}
        sql("INSERT INTO m2.account(id,user_code,display_name,department_id,email,password_hash) SELECT gen_random_uuid(),'business-route-'||n,'批量收件夹具','"+deptA+"','same@example.invalid',password_hash FROM generate_series(1,1001)n CROSS JOIN m2.account WHERE id='"+adminId+"'");sql("INSERT INTO m2.role_binding(id,account_id,role_id) SELECT gen_random_uuid(),id,'"+manager+"' FROM m2.account WHERE user_code LIKE 'business-route-%'");
        try{assertCode(route(event("WorkOrderCompletionSubmitted")),400,"VALIDATION_ERROR");}finally{sql("DELETE FROM m2.role_binding WHERE account_id IN (SELECT id FROM m2.account WHERE user_code LIKE 'business-route-%')");}
        assertEquals(List.of(owner,manager),recipientIds(route(event("WorkOrderCompletionSubmitted"))));
    }
    @Test @Order(12) void sameProcessPortsExecuteRealLogicAndRejectCallerTransactions()throws Exception{
        var source=new DriverManagerDataSource(database,System.getenv("DB_MIGRATION_USERNAME"),System.getenv("DB_MIGRATION_PASSWORD"));var jdbc=new JdbcTemplate(source);var tx=new TransactionTemplate(new DataSourceTransactionManager(source));var identities=new IdentityRepository(jdbc);var service=new IdentityService(identities,new AuthRateLimiter(jdbc),new AuthAudit(jdbc,json,identities),new Credentials(),tx);var env=new MockEnvironment().withProperty("G4_BASE_URL","http://127.0.0.1:"+g4.getAddress().getPort()).withProperty("G4_SERVICE_G5_TOKEN",g4Token);var sut=new AuthorizationService(service,identities,new AuthorizationRepository(jdbc,identities),new RoomScopeClient(env,json),tx);
        AuthorizationPort authorization=sut;IdentityPort qualifications=new IdentityFacade(service,sut);assertTrue(authorization.check(student.token(),batch(owner,List.of(check("reservation:create",roomA))),UUID.randomUUID()).items().getFirst().allowed());assertTrue(qualifications.eligibility(qualification(owner,false)).eligible());
        int before=calls.get();assertThrows(IllegalStateException.class,()->tx.execute(status->authorization.check(student.token(),batch(owner,List.of(check("reservation:create",roomA))),UUID.randomUUID())));assertThrows(IllegalStateException.class,()->tx.execute(status->qualifications.eligibility(qualification(owner,false))));assertEquals(before,calls.get());
        duringRpc.set(()->{try{sql("SET lock_timeout='1s'; UPDATE m2.account SET display_name=display_name WHERE id='"+owner+"'");}catch(Exception bad){throw new RuntimeException(bad);}});assertTrue(authorization.check(student.token(),batch(owner,List.of(check("room:read",roomA))),UUID.randomUUID()).items().getFirst().allowed());
        String events=scalar("SELECT coalesce(string_agg(event::text,''),'') FROM m2.g5_audit_event");for(String secret:List.of(password,hmac,g4Token,student.token(),assigner.token()))assertFalse(events.contains(secret));for(String token:serviceTokens.values())assertFalse(events.contains(token));
    }

    @Test @Order(13) void currentRoomOwnershipAndCrossRolePermissionUnionAreRecomputed()throws Exception{
        mode.set("DEPT_B");try{
            assertEquals(false,items(authorize(owner,student,List.of(check("reservation:create",roomA)))).getFirst().get("allowed"));
            var current=items(authorize(manager,assigner,List.of(check("resource:trace",roomA),check("workorder:assign",roomA))));assertEquals(List.of(false,true),current.stream().map(i->i.get("allowed")).toList());
        }finally{mode.set("OK");}
        assertEquals(true,items(authorize(manager,assigner,List.of(check("resource:trace",roomA)))).getFirst().get("allowed"));
        String extraRole=uid(401);sql("INSERT INTO m2.role(id,key,name) VALUES ('"+extraRole+"','BUS_EXTRA_READ','跨角色工单查看')");sql("INSERT INTO m2.role_binding(id,account_id,role_id) VALUES (gen_random_uuid(),'"+operator+"','"+extraRole+"')");
        sql("UPDATE m2.role_grant SET role_id='"+extraRole+"' WHERE role_id='"+operator+"' AND action='workorder:read'");
        var escalation=event("WorkOrderEscalated");payload(escalation).put("type","REPAIR_OVERDUE");assertTrue(recipientIds(route(escalation)).contains(operator));
        sql("DELETE FROM m2.role_binding WHERE account_id='"+operator+"' AND role_id='"+extraRole+"'");
        try{assertFalse(recipientIds(route(escalation)).contains(operator));payload(escalation).put("assigneeId",operator);var result=(List<Map<String,Object>>)data(route(escalation)).get("recipients");assertEquals(List.of("ASSIGNEE"),result.stream().filter(i->i.get("userId").equals(operator)).findFirst().orElseThrow().get("branches"));}
        finally{sql("UPDATE m2.role_grant SET role_id='"+operator+"' WHERE role_id='"+extraRole+"'");}
    }
    @Test @Order(14) void hundredChecksSingleFactsAndUnknownRoomsDoNotYieldPartialResults()throws Exception{
        var checks=new ArrayList<Map<String,Object>>();for(int n=0;n<100;n++)checks.add(check("room:read",roomA));var result=items(authorize(owner,student,checks));assertEquals(100,result.size());assertTrue(result.stream().allMatch(i->i.get("allowed").equals(true)));assertEquals(checks.stream().map(i->i.get("checkId")).toList(),result.stream().map(i->i.get("checkId")).toList());
        for(String state:List.of("MALFORMED","FAIL")){mode.set(state);try{assertCode(eligible(owner,false),503,"SCOPE_FACTS_UNAVAILABLE");assertCode(request("POST","/internal/maintenance/assignee-candidates",Map.of("actorUserId",manager,"roomId",roomA),null,actorHeaders("G3",assigner)),503,"SCOPE_FACTS_UNAVAILABLE");}finally{mode.set("OK");}}
        var unknown=event("WorkOrderReturned");unknown.put("roomId",uid(299));((Map<String,Object>)unknown.get("audit")).put("roomId",uid(299));assertCode(route(unknown),404,"NOT_FOUND");
        assertCode(request("POST","/internal/maintenance/assignee-eligibility",Map.of("actorUserId",manager,"assigneeId",uid(777),"roomId",roomA),null,actorHeaders("G3",assigner)),404,"NOT_FOUND");
    }

    private Session login(String code,String password) throws Exception {var r=request("POST","/api/m2/auth/login",Map.of("account",code,"password",password),null,null);assertEquals(200,r.statusCode(),r.body());String cookie=r.headers().firstValue("Set-Cookie").orElseThrow();return new Session(cookie.substring(11,cookie.indexOf(';')),JsonPath.read(r.body(),"$.data.csrfToken"));}
    private HttpResponse<String> request(String method,String path,Object body,Session session,Map<String,String> headers) throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(15));
        if(session!=null){request.header("Cookie","PJ_SESSION="+session.token());if(session.csrf()!=null)request.header("X-CSRF-Token",session.csrf());}if(headers!=null)headers.forEach(request::header);
        if(body!=null)request.header("Content-Type","application/json");request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        return http.send(request.build(),HttpResponse.BodyHandlers.ofString());
    }
    private void startBackend() throws Exception {
        port=freePort();do{managementPort=freePort();}while(managementPort==port);Path jar=Path.of(System.getProperty("appJar")).toAbsolutePath();
        var command=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString(),"-jar",jar.toString());
        command.environment().putAll(Map.of("DB_URL",database,"DB_MIGRATION_URL",database,"APP_BIND_ADDRESS","127.0.0.1","APP_PORT",Integer.toString(port),"MANAGEMENT_PORT",Integer.toString(managementPort),"G5_BOOTSTRAP_ENABLED","true","G5_BOOTSTRAP_ACCOUNT","business-manager","G5_BOOTSTRAP_PASSWORD",password,"G5_IDEMPOTENCY_HMAC_KEY",hmac));
        serviceTokens.forEach((group,token)->command.environment().put("G5_SERVICE_"+group+"_TOKEN",token));command.environment().put("G4_BASE_URL","http://127.0.0.1:"+g4.getAddress().getPort());command.environment().put("G4_SERVICE_G5_TOKEN",g4Token);command.redirectErrorStream(true).redirectOutput(jar.getParent().resolve("business-authorization-it-"+port+".log").toFile());process=command.start();
        long deadline=System.nanoTime()+Duration.ofSeconds(90).toNanos();while(System.nanoTime()<deadline){assertTrue(process.isAlive(),"Configuration backend exited; inspect ignored log");try{if(http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+managementPort+"/actuator/health/readiness")).GET().build(),HttpResponse.BodyHandlers.ofString()).statusCode()==200)return;}catch(IOException ignored){}Thread.sleep(200);}fail("Config backend readiness timeout");
    }
    private void stopBackend() throws Exception {if(process!=null&&process.isAlive()){process.destroy();if(!process.waitFor(15,TimeUnit.SECONDS)){process.destroyForcibly();assertTrue(process.waitFor(10,TimeUnit.SECONDS));}}}
    private int freePort() throws IOException {try(var socket=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))){return socket.getLocalPort();}}
    private void sql(String query) throws Exception {try(var connection=DriverManager.getConnection(database,System.getenv("DB_MIGRATION_USERNAME"),System.getenv("DB_MIGRATION_PASSWORD"));var statement=connection.createStatement()){statement.execute(query);}}
    private String scalar(String query) throws Exception {try(var connection=DriverManager.getConnection(database,System.getenv("DB_MIGRATION_USERNAME"),System.getenv("DB_MIGRATION_PASSWORD"));var statement=connection.createStatement();var rows=statement.executeQuery(query)){assertTrue(rows.next());return rows.getString(1);}}
    private long count(String query) throws Exception {return Long.parseLong(scalar(query));}
}
