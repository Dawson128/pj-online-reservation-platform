package cn.edu.campus.approval.g5.domain;

import java.util.*;
import org.springframework.stereotype.Component;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.*;
import static cn.edu.campus.approval.g5.domain.BusinessInput.*;

/** Full nineteen-variant consumer schema plus envelope/audit identity invariants. Never logs payloads. */
@org.springframework.context.annotation.Profile("!shell")
@Component
public class NotificationEventRules {
    private final ObjectMapper json=new ObjectMapper();
    private final JsonSchema schema;
    private final JsonNode registry;
    public NotificationEventRules(){
        try(var input=getClass().getResourceAsStream("/g5/events/IncomingNotificationEvent.schema.json");var manifest=getClass().getResourceAsStream("/g5/events/notification-registry.json")){
            schema=JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(input,SchemaValidatorsConfig.builder().formatAssertionsEnabled(true).build());
            registry=json.readTree(manifest);
        }catch(Exception invalid){throw new IllegalStateException("G5 notification contracts cannot be loaded",invalid);}
    }
    public Map<String,Object> require(Object value){
        var event=object(value);
        try {if(!schema.validate(json.valueToTree(event)).isEmpty())throw invalid();}catch(IllegalArgumentException bad){throw invalid();}
        var audit=object(event.get("audit"));var aggregate=object(event.get("aggregate"));var payload=object(event.get("payload"));
        if(!Objects.equals(event.get("eventId"),audit.get("id"))||!Objects.equals(aggregate,audit.get("source")))throw invalid();
        for(String key:List.of("producer","roomId","actorId","actorKind","traceId","operationId"))if(!Objects.equals(event.get(key),audit.get(key)))throw invalid();
        boolean system=event.get("roomId")==null;if(!Objects.equals(audit.get("scopeKind"),system?"SYSTEM":"ROOM"))throw invalid();
        if("USER".equals(event.get("actorKind"))!=(event.get("actorId")!=null))throw invalid();
        String type=(String)event.get("eventType");var definition=registry.path(type);String sourceKey=definition.path("sourceKey").asText(null);
        if(sourceKey!=null&&!Objects.equals(aggregate.get("id"),payload.get(sourceKey)))throw invalid();
        if(type.equals("ApprovalTaskOpened")){
            int level=((Number)payload.get("level")).intValue();var excluded=list(payload.get("excludedActorIds"),1,2);
            if(excluded.size()!=level||new HashSet<>(excluded).size()!=level||!excluded.contains(payload.get("applicantId")))throw invalid();
        }
        if(type.equals("WorkOrderCompletionSubmitted")){var actual=(List<?>)payload.get("actualRepairerIds");if(new HashSet<>(actual).size()!=actual.size())throw invalid();}
        if(type.equals("ConfigChanged")&&(!Objects.equals(payload.get("configVersion"),event.get("aggregateVersion"))||!Objects.equals(payload.get("effectiveAt"),audit.get("effectiveAt"))))throw invalid();
        if(type.equals("RecoveryAttention")&&!Objects.equals(payload.get("operationId"),event.get("operationId")))throw invalid();
        return event;
    }
}
