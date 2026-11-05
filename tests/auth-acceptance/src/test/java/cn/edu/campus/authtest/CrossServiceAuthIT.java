package cn.edu.campus.authtest;

import cn.edu.campus.security.AuthFailure;
import cn.edu.campus.security.G5IntrospectionClient;
import java.io.IOException;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

/** Real G5 database + three separately packaged HTTP services. No mocked identity or CSRF core. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CrossServiceAuthIT {
    private final JsonMapper json=new JsonMapper();
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).build();
    private final List<Process> processes=new ArrayList<>();
    private final List<String> secrets=new ArrayList<>();
    private final Map<String,String> credentials=new HashMap<>();
    private final Map<String,String> rolePasswords=new HashMap<>();
    private final String prefix="g6it_"+UUID.randomUUID().toString().replace("-","").substring(0,12)+"_";
    private final String password="it-"+UUID.randomUUID().toString().replace("-","").substring(0,18);
    private String dbUrl,dbUser,dbPassword;
    private Path root,logs;
    private int approvalPort,reservationPort,maintenancePort;
    private Process approval;
    private record Session(String token,String csrf,String userId) {
        @Override public String toString(){return "Session[credentials=REDACTED]";}
    }

    @BeforeAll void start() throws Exception {
        dbUrl=System.getenv("G6_AUTH_TEST_DB_URL");dbUser=System.getenv("G6_AUTH_TEST_DB_USERNAME");dbPassword=System.getenv("G6_AUTH_TEST_DB_PASSWORD");
        assertTrue(dbUrl!=null&&dbUrl.matches("jdbc:postgresql://[^/]+/pj_g6_auth_it_[a-zA-Z0-9_]+(?:\\?.*)?"),"Requires an isolated pj_g6_auth_it_* database, never campus or production");
        assertNotNull(dbUser,"Missing test database username");assertNotNull(dbPassword,"Missing test database password");
        root=Path.of(System.getProperty("repoRoot")).toAbsolutePath().normalize();logs=Path.of("target","auth-it-logs").toAbsolutePath();Files.createDirectories(logs);
        secrets.add(password);secrets.add(dbPassword);
        try(Connection connection=connection();Statement sql=connection.createStatement()) {
            try(ResultSet rows=sql.executeQuery("SELECT count(*) FROM information_schema.schemata WHERE schema_name IN ('m1','m2','m3')")) {
                rows.next();assertEquals(0,rows.getInt(1),"Test database must start empty; existing data is not reset");
            }
            String database=dbUrl.substring(dbUrl.lastIndexOf('/')+1).split("\\?")[0];
            assertTrue(database.matches("pj_g6_auth_it_[a-zA-Z0-9_]+"));
            for(int number=1;number<=3;number++) {
                String schema="m"+number,app=prefix+"app_"+schema,migrate=prefix+"migrate_"+schema;
                String appPassword=UUID.randomUUID().toString().replace("-",""),migrationPassword=UUID.randomUUID().toString().replace("-","");
                rolePasswords.put(app,appPassword);rolePasswords.put(migrate,migrationPassword);secrets.add(appPassword);secrets.add(migrationPassword);
                sql.execute("CREATE ROLE "+app+" LOGIN PASSWORD '"+appPassword+"'");
                sql.execute("CREATE ROLE "+migrate+" LOGIN PASSWORD '"+migrationPassword+"'");
                sql.execute("GRANT CONNECT ON DATABASE "+database+" TO "+app+","+migrate);
                sql.execute("CREATE SCHEMA "+schema+" AUTHORIZATION "+migrate);
                sql.execute("GRANT USAGE ON SCHEMA "+schema+" TO "+app);
                sql.execute("ALTER DEFAULT PRIVILEGES FOR ROLE "+migrate+" IN SCHEMA "+schema+" GRANT SELECT,INSERT,UPDATE,DELETE ON TABLES TO "+app);
                sql.execute("ALTER DEFAULT PRIVILEGES FOR ROLE "+migrate+" IN SCHEMA "+schema+" GRANT USAGE,SELECT ON SEQUENCES TO "+app);
            }
        }
        for(String group:List.of("G1","G3","G4","G6")){String secret=UUID.randomUUID().toString()+UUID.randomUUID();credentials.put(group,secret);secrets.add(secret);}
        approvalPort=freePort();int management=freePort();
        Map<String,String> approvalEnv=environment("m2");approvalEnv.putAll(Map.of("G5_BOOTSTRAP_ENABLED","true","G5_BOOTSTRAP_ACCOUNT","g6-auth-admin","G5_BOOTSTRAP_PASSWORD",password,"MANAGEMENT_PORT",Integer.toString(management)));
        approval=launch("approval",approvalPort,approvalEnv,false);
        await(approval,management);
        reservationPort=freePort();reservationPort=launchConsumer("reservation","m1","G4",reservationPort);
        maintenancePort=freePort();maintenancePort=launchConsumer("maintenance","m3","G3",maintenancePort);
    }
    private Map<String,String> environment(String schema) {
        String app=prefix+"app_"+schema,migrate=prefix+"migrate_"+schema;
        Map<String,String> env=new HashMap<>(Map.of("DATABASE_URL",dbUrl,"MIGRATION_DATABASE_URL",dbUrl,
                "DATABASE_USERNAME",app,"DATABASE_PASSWORD",rolePasswords.get(app),"MIGRATION_USERNAME",migrate,"MIGRATION_PASSWORD",rolePasswords.get(migrate),
                "SERVER_ADDRESS","127.0.0.1","G5_AUTH_BASE_URL","http://127.0.0.1:"+approvalPort));
        for(var credential:credentials.entrySet())env.put("G5_SERVICE_"+credential.getKey()+"_TOKEN",credential.getValue());
        return env;
    }
    private int launchConsumer(String service,String schema,String group,int port) throws Exception {
        var env=environment(schema);env.put("AUTH_IT_MODULE",group);
        Process process=launch(service,port,env,true);await(process,port);return port;
    }
    private Process launch(String service,int port,Map<String,String> env,boolean probe) throws IOException {
        Path jar=root.resolve("services/"+service+"/target/"+service+"-service-0.1.0-SNAPSHOT.jar");
        assertTrue(Files.isRegularFile(jar),"Build all services before authentication acceptance");
        String java=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString();
        List<String> args=new ArrayList<>(List.of(java));
        if(probe)args.addAll(List.of("-Dloader.path="+Path.of("target/test-classes").toAbsolutePath(),"-cp",jar.toString(),"org.springframework.boot.loader.launch.PropertiesLauncher","--spring.profiles.active=auth-it","--spring.main.sources="+AuthProbeConfiguration.class.getName()));
        else args.addAll(List.of("-jar",jar.toString()));
        args.add("--server.port="+port);
        ProcessBuilder builder=new ProcessBuilder(args);builder.environment().putAll(env);
        // All credentials are environment-only; stdout/stderr are checked for leaks after shutdown.
        builder.redirectErrorStream(true).redirectOutput(logs.resolve(service+".log").toFile());
        Process process=builder.start();processes.add(process);return process;
    }
    private void await(Process process,int port) throws Exception {
        long end=System.nanoTime()+Duration.ofSeconds(60).toNanos();
        while(System.nanoTime()<end) {
            assertTrue(process.isAlive(),"Service exited; inspect target/auth-it-logs");
            try{if(send(port,"GET","/actuator/health/liveness",null,null,null).statusCode()==200)return;}catch(IOException ignored){}
            Thread.sleep(200);
        }
        fail("Service failed to start; inspect target/auth-it-logs");
    }
    private Connection connection() throws SQLException {return DriverManager.getConnection(dbUrl,dbUser,dbPassword);}
    private static int freePort() throws IOException {try(ServerSocket socket=new ServerSocket(0,0,InetAddress.getLoopbackAddress())){return socket.getLocalPort();}}
    private HttpResponse<String> send(int port,String method,String path,Object body,Session session,String csrf) throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(8));
        if(session!=null)request.header("Cookie","PJ_SESSION="+session.token());
        if(csrf!=null)request.header("X-CSRF-Token",csrf);
        if(body!=null)request.header("Content-Type","application/json");
        request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        return http.send(request.build(),HttpResponse.BodyHandlers.ofString());
    }
    private Session login() throws Exception {
        var response=send(approvalPort,"POST","/api/m2/auth/login",Map.of("account","g6-auth-admin","password",password),null,null);
        assertEquals(200,response.statusCode(),"Real G5 login must succeed");JsonNode data=json.readTree(response.body()).path("data");
        String cookie=response.headers().firstValue("Set-Cookie").orElseThrow().split(";",2)[0].substring("PJ_SESSION=".length());
        Session session=new Session(cookie,data.path("csrfToken").asText(),data.path("principal").path("userId").asText());
        secrets.add(cookie);secrets.add(session.csrf());return session;
    }
    private String path(int port,String suffix){return "/api/"+(port==reservationPort?"m1":"m3")+"/__auth_acceptance/"+suffix;}
    private void code(HttpResponse<String> response,int status,String code) throws Exception {assertEquals(status,response.statusCode(),"Unexpected HTTP status (credential-bearing bodies omitted)");assertEquals(code,json.readTree(response.body()).path("code").asText());}
    private HttpResponse<String> introspect(Map<String,Object> body,String serviceCredential) throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+approvalPort+"/internal/auth/introspect"))
                .timeout(Duration.ofSeconds(8)).header("Content-Type","application/json").header("Authorization","Bearer "+serviceCredential)
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
        return http.send(request,HttpResponse.BodyHandlers.ofString());
    }

    @Test @Order(1) void legacyIntrospectionAndNewAtomicCsrfContractWork() throws Exception {
        Session one=login(),two=login();String credential=credentials.get("G4");
        assertEquals(200,introspect(Map.of("sessionToken",one.token()),credential).statusCode());
        assertEquals(200,introspect(Map.of("sessionToken",one.token(),"requestMethod","POST","csrfToken",one.csrf()),credential).statusCode());
        code(introspect(Map.of("sessionToken",one.token(),"requestMethod","POST"),credential),403,"FORBIDDEN");
        var wrong=introspect(Map.of("sessionToken",one.token(),"requestMethod","POST","csrfToken",two.csrf()),credential);
        code(wrong,403,"FORBIDDEN");assertEquals("csrfToken",json.readTree(wrong.body()).path("details").path("fields").get(0).path("field").asText());
        code(introspect(Map.of("sessionToken",one.token(),"requestMethod","TRACE"),credential),400,"VALIDATION_ERROR");
        Map<String,Object> explicitNull=new HashMap<>();explicitNull.put("sessionToken",one.token());explicitNull.put("requestMethod",null);
        code(introspect(explicitNull,credential),400,"VALIDATION_ERROR");
    }
    @Test @Order(2) void sameRealSessionWorksOnBothServices() throws Exception {
        Session session=login();
        for(int port:List.of(reservationPort,maintenancePort)) {
            var response=send(port,"GET",path(port,"read"),null,session,null);assertEquals(200,response.statusCode());
            assertEquals(session.userId(),json.readTree(response.body()).path("userId").asText());
            assertTrue(response.headers().allValues("Set-Cookie").stream().noneMatch(value->value.contains("JSESSIONID")));
        }
    }
    @Test @Order(3) void allUnsafeMethodsNeedSessionBoundCsrf() throws Exception {
        Session session=login(),other=login();
        for(int port:List.of(reservationPort,maintenancePort))for(String method:List.of("POST","PUT","PATCH","DELETE")) {
            code(send(port,method,path(port,"write"),Map.of(),session,null),403,"FORBIDDEN");
            code(send(port,method,path(port,"write"),Map.of(),session,other.csrf()),403,"FORBIDDEN");
            assertEquals(200,send(port,method,path(port,"write"),Map.of(),session,session.csrf()).statusCode());
        }
    }
    @Test @Order(4) void forgedHeadersDuplicateCookiesAndUnregisteredPathsStayClosed() throws Exception {
        Session session=login();
        for(int port:List.of(reservationPort,maintenancePort)) {
            var forged=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path(port,"read"))).header("X-User-Id",session.userId()).header("Authorization","Bearer "+session.token()).GET().build();
            code(http.send(forged,HttpResponse.BodyHandlers.ofString()),401,"UNAUTHENTICATED");
            var duplicate=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path(port,"read"))).header("Cookie","PJ_SESSION="+session.token()+"; PJ_SESSION="+session.token()).GET().build();
            code(http.send(duplicate,HttpResponse.BodyHandlers.ofString()),401,"UNAUTHENTICATED");
            code(send(port,"GET",path(port,"unregistered"),null,session,null),401,"UNAUTHENTICATED");
            code(send(port,"GET","/internal/events",null,session,null),401,"UNAUTHENTICATED");
        }
    }
    @Test @Order(5) void systemAdministratorDoesNotImplicitlyGainRoomManagement() throws Exception {
        Session session=login();for(int port:List.of(reservationPort,maintenancePort))code(send(port,"GET",path(port,"denied"),null,session,null),403,"FORBIDDEN");
    }
    @Test @Order(6) void logoutImmediatelyInvalidatesBothConsumers() throws Exception {
        Session session=login();assertEquals(200,send(approvalPort,"POST","/api/m2/auth/logout",Map.of(),session,session.csrf()).statusCode());
        for(int port:List.of(reservationPort,maintenancePort))code(send(port,"GET",path(port,"read"),null,session,null),401,"UNAUTHENTICATED");
    }
    @Test @Order(7) void expiryAndAccountDisableAreNotCached() throws Exception {
        Session expired=login();
        try(Connection conn=connection();PreparedStatement sql=conn.prepareStatement("UPDATE m2.session SET expires_at=created_at+interval '1 millisecond' WHERE account_id=?")){sql.setObject(1,UUID.fromString(expired.userId()));sql.executeUpdate();}
        for(int port:List.of(reservationPort,maintenancePort))code(send(port,"GET",path(port,"read"),null,expired,null),401,"UNAUTHENTICATED");
        Session disabled=login();
        try(Connection conn=connection();PreparedStatement sql=conn.prepareStatement("UPDATE m2.account SET enabled=false WHERE id=?")){sql.setObject(1,UUID.fromString(disabled.userId()));sql.executeUpdate();}
        try{for(int port:List.of(reservationPort,maintenancePort))code(send(port,"GET",path(port,"read"),null,disabled,null),401,"UNAUTHENTICATED");}
        finally{try(Connection conn=connection();PreparedStatement sql=conn.prepareStatement("UPDATE m2.account SET enabled=true WHERE id=?")){sql.setObject(1,UUID.fromString(disabled.userId()));sql.executeUpdate();}}
    }
    @Test @Order(8) void permissionVersionChangesRevokePreviousRequests() throws Exception {
        Session session=login();for(int port:List.of(reservationPort,maintenancePort))assertEquals(200,send(port,"GET",path(port,"read"),null,session,null).statusCode());
        try(Connection conn=connection();PreparedStatement sql=conn.prepareStatement("UPDATE m2.account SET permission_version=permission_version+1 WHERE id=?")){sql.setObject(1,UUID.fromString(session.userId()));sql.executeUpdate();}
        for(int port:List.of(reservationPort,maintenancePort))code(send(port,"GET",path(port,"read"),null,session,null),401,"UNAUTHENTICATED");
    }
    @Test @Order(9) void wrongServiceCredentialIsNotMisreportedAsUserCsrfFailure() throws Exception {
        Session session=login();var client=new G5IntrospectionClient(URI.create("http://127.0.0.1:"+approvalPort),false,module->"wrong-"+UUID.randomUUID());
        AuthFailure failure=assertThrows(AuthFailure.class,()->client.authenticate("G4",session.token(),"GET",null));assertEquals(503,failure.status());
    }
    @Test @Order(99) void providerFailureDoesNotFallBackToPreviouslyAuthenticatedIdentity() throws Exception {
        Session session=login();for(int port:List.of(reservationPort,maintenancePort))assertEquals(200,send(port,"GET",path(port,"read"),null,session,null).statusCode());
        stop(approval);
        for(int port:List.of(reservationPort,maintenancePort)) {
            code(send(port,"GET",path(port,"read"),null,session,null),503,"AUTH_UNAVAILABLE");
            code(send(port,"POST",path(port,"write"),Map.of(),session,session.csrf()),503,"AUTH_UNAVAILABLE");
        }
    }
    private void stop(Process process) throws Exception {if(process!=null&&process.isAlive()){process.destroy();if(!process.waitFor(10,java.util.concurrent.TimeUnit.SECONDS)){process.destroyForcibly();process.waitFor();}}}
    @AfterAll void cleanup() throws Exception {
        for(Process process:processes)stop(process);
        if(logs!=null)try(var files=Files.list(logs)) {for(Path file:files.toList()){String content=Files.readString(file);for(String secret:secrets)assertFalse(content.contains(secret),"Credential leaked to service log (value redacted)");}}
    }
}
