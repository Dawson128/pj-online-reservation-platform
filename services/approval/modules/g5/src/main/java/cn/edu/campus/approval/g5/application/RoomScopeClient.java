package cn.edu.campus.approval.g5.application;

import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.*;
import cn.edu.campus.approval.g5.api.*;
import cn.edu.campus.approval.g5.api.BusinessFacts.ScopeFacts;
import cn.edu.campus.approval.g5.domain.Credentials;

/** G4-I02/I08 consumer. No session, retries, redirects, or positive cache; never under DB transactions. */
@org.springframework.context.annotation.Profile("!shell")
@Component
public class RoomScopeClient {
    private final Environment environment;
    private final ObjectMapper json;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    public RoomScopeClient(Environment environment,ObjectMapper json){this.environment=environment;this.json=json;}
    public static void outsideTransaction(){if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Prepare remote G5 facts before opening the consumer transaction");}
    public void requireAll(List<UUID> rooms,UUID trace){
        long deadline=System.nanoTime()+Duration.ofSeconds(5).toNanos();
        for(UUID room:rooms){long remaining=deadline-System.nanoTime();if(remaining<=0)throw unavailable();
            if(single(room,"AUTHORIZATION",null,null,trace,Duration.ofNanos(remaining))==null)throw Credentials.invalid("grants","指定房间尚未登记");}
    }
    public ScopeFacts authorization(UUID room,UUID trace){return require(single(room,"AUTHORIZATION",null,null,trace,Duration.ofSeconds(5)));}
    public ScopeFacts notification(UUID room,String eventType,UUID eventId,UUID trace){return require(single(room,"NOTIFICATION_ROUTING",eventType,eventId,trace,Duration.ofSeconds(5)));}
    private ScopeFacts require(ScopeFacts facts){if(facts==null)throw new IdentityException(404,"NOT_FOUND","房间不存在");return facts;}
    private ScopeFacts single(UUID room,String purpose,String eventType,UUID eventId,UUID trace,Duration timeout){
        String query="purpose="+purpose+"&traceId="+trace;
        if(eventType!=null)query+="&eventType="+eventType+"&eventId="+eventId;
        var data=send("/internal/rooms/"+room+"/scope-facts?"+query,null,timeout,true);
        return data==null?null:parse(room,data);
    }
    public Map<UUID,ScopeFacts> batch(Collection<UUID> input,UUID trace){
        Set<UUID> rooms=new LinkedHashSet<>(input);if(rooms.isEmpty())return Map.of();if(rooms.size()>100)throw unavailable();
        var data=send("/internal/rooms/scope-facts/query",Map.of("purpose","AUTHORIZATION","roomIds",rooms,"traceId",trace),Duration.ofSeconds(5),false);
        try {
            exact(data,Set.of("items","asOf"));OffsetDateTime.parse(data.path("asOf").asText());
            if(!data.path("items").isArray()||data.path("items").size()!=rooms.size())throw unavailable();
            Map<UUID,ScopeFacts> result=new LinkedHashMap<>();
            for(var item:data.path("items")){exact(item,Set.of("roomId","facts"));UUID room=uuid(item.path("roomId"));
                if(!rooms.contains(room)||result.containsKey(room))throw unavailable();result.put(room,item.path("facts").isNull()?null:parse(room,item.path("facts")));}
            if(!result.keySet().equals(rooms))throw unavailable();return result;
        }catch(IdentityException failure){throw failure;}catch(RuntimeException failure){throw unavailable();}
    }
    private JsonNode send(String path,Object body,Duration timeout,boolean allowMissing){
        outsideTransaction();String base=environment.getProperty("G4_BASE_URL"),secret=environment.getProperty("G4_SERVICE_G5_TOKEN");
        if(base==null||secret==null||secret.length()<32||secret.length()>256)throw unavailable();
        try {
            URI origin=URI.create(base);
            if(!Set.of("http","https").contains(origin.getScheme())||origin.getHost()==null||origin.getUserInfo()!=null||origin.getQuery()!=null||origin.getFragment()!=null||!Set.of("","/").contains(origin.getPath()))throw unavailable();
            var request=HttpRequest.newBuilder(origin.resolve(path)).timeout(timeout).header("Authorization","Bearer "+secret).header("Accept","application/json");
            if(body==null)request.GET();else request.header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
            var response=http.send(request.build(),HttpResponse.BodyHandlers.ofString());
            if(allowMissing&&response.statusCode()==404)return null;if(response.statusCode()!=200)throw unavailable();
            var envelope=json.readTree(response.body());if(!envelope.isObject()||!envelope.has("data"))throw unavailable();return envelope.path("data");
        }catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw unavailable();}
        catch(IdentityException failure){throw failure;}catch(IOException|RuntimeException failure){throw unavailable();}
    }
    private ScopeFacts parse(UUID room,JsonNode facts){
        try {
            exact(facts,Set.of("roomId","schoolWide","deptId","enabled","roomVersion","asOf"));
            if(!room.equals(uuid(facts.path("roomId")))||!facts.path("schoolWide").isBoolean()||!facts.path("enabled").isBoolean()||!facts.path("roomVersion").isString()||!facts.path("roomVersion").asText().matches("[1-9][0-9]{0,18}"))throw unavailable();
            Long.parseLong(facts.path("roomVersion").asText());OffsetDateTime.parse(facts.path("asOf").asText());
            boolean school=facts.path("schoolWide").asBoolean();UUID dept=null;
            if(school){if(!facts.path("deptId").isNull())throw unavailable();}else dept=uuid(facts.path("deptId"));
            return new ScopeFacts(room,school,dept,facts.path("enabled").asBoolean(),facts.path("roomVersion").asText(),facts.path("asOf").asText());
        }catch(IdentityException failure){throw failure;}catch(RuntimeException failure){throw unavailable();}
    }
    private UUID uuid(JsonNode value){if(!value.isString()||!value.asText().matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))throw unavailable();return UUID.fromString(value.asText());}
    private void exact(JsonNode node,Set<String> fields){if(!node.isObject()||!node.propertyNames().equals(fields))throw unavailable();}
    private IdentityException unavailable(){return new IdentityException(503,"SCOPE_FACTS_UNAVAILABLE","暂时无法核验房间范围，请稍后重试");}
}
