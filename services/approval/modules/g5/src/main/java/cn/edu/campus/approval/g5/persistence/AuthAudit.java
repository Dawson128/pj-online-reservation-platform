package cn.edu.campus.approval.g5.persistence;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

/** Only allowlisted metadata enters AuditRecorded; no credential/request-body arguments. */
@org.springframework.context.annotation.Profile("!shell")
@Repository
public class AuthAudit {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final IdentityRepository identities;
    private static final UUID SYSTEM_ID = UUID.nameUUIDFromBytes("G5-AUTH".getBytes(StandardCharsets.UTF_8));

    public AuthAudit(JdbcTemplate jdbc, ObjectMapper json, IdentityRepository identities) {
        this.jdbc = jdbc;
        this.json = json;
        this.identities = identities;
    }
    public void record(String action, UUID actor, long version, String result, String code, UUID trace,
                       boolean passwordChanged) {
        record(action, actor, actor, version, result, code, trace, passwordChanged);
    }
    public void bootstrap(UUID account, UUID trace) {
        record("account:bootstrap", null, account, 1, "SUCCESS", null, trace, false);
    }
    public void management(String action, UUID actor, UUID subject, long version, UUID trace,
                           boolean department, List<Map<String,Object>> before, List<Map<String,Object>> after) {
        write(action,actor,subject,version,"SUCCESS",null,trace,department ? "SYSTEM" : "ACCOUNT",before,after);
    }
    @SuppressWarnings("unchecked") public void configuration(UUID actor,UUID trace,Map<String,Object> before,Map<String,Object> after) {
        Map<String,Integer> oldValues=(Map<String,Integer>)before.get("values"),newValues=(Map<String,Integer>)after.get("values");
        List<String> changed=newValues.keySet().stream().filter(key->!newValues.get(key).equals(oldValues.get(key))).sorted().toList();
        if(changed.isEmpty()) throw new IllegalStateException("ConfigChanged requires an actual change");
        UUID config=UUID.nameUUIDFromBytes("G5:business-config".getBytes(StandardCharsets.UTF_8));
        write("G5-P19",actor,config,Long.parseLong((String)after.get("version")),"SUCCESS",null,trace,"CONFIG",configFields(before),configFields(after),"ConfigChanged",
                Map.of("configVersion",after.get("version"),"effectiveAt",after.get("effectiveAt"),"changedKeys",changed),(String)after.get("effectiveAt"));
    }
    public void recovery(UUID actor,UUID operation,UUID trace,String reason) {
        write("G5-L05",actor,operation,1,"SUCCESS",null,trace,"SYSTEM",List.of(),List.of(),"AuditRecorded",Map.of(),null,reason,operation);
    }
    public void recoveryRejected(UUID actor,UUID operation,UUID trace,String code) {
        write("G5-L05",actor,operation,1,"FAILURE",code,trace,"SYSTEM",List.of(),List.of(),"AuditRecorded",Map.of(),null,null,operation);
    }
    private List<Map<String,Object>> configFields(Map<String,Object> record) {
        List<Map<String,Object>> result=new java.util.ArrayList<>();
        result.add(Map.of("name","version","value",record.get("version")));result.add(Map.of("name","effectiveAt","value",record.get("effectiveAt")));
        ((Map<?,?>)record.get("values")).forEach((key,value)->result.add(Map.of("name",key.toString(),"value",value.toString())));
        return result;
    }
    private void record(String action, UUID actor, UUID subject, long version, String result, String code,
                        UUID trace, boolean passwordChanged) {
        List<?> after = passwordChanged ? List.of(Map.of("name", "password", "value", "已变更")) : List.of();
        write(action,actor,subject,version,result,code,trace,subject == null ? "SYSTEM" : "ACCOUNT",List.of(),after);
    }
    private void write(String action, UUID actor, UUID subject, long version, String result, String code,
                       UUID trace, String sourceType, List<?> before, List<?> after) {
        write(action,actor,subject,version,result,code,trace,sourceType,before,after,"AuditRecorded",Map.of(),null);
    }
    private void write(String action,UUID actor,UUID subject,long version,String result,String code,UUID trace,String sourceType,List<?> before,List<?> after,String eventType,Map<String,Object> payload,String effectiveAt) {
        write(action,actor,subject,version,result,code,trace,sourceType,before,after,eventType,payload,effectiveAt,null,null);
    }
    private void write(String action,UUID actor,UUID subject,long version,String result,String code,UUID trace,String sourceType,List<?> before,List<?> after,String eventType,Map<String,Object> payload,String effectiveAt,String reason,UUID operationId) {
        UUID eventId = UUID.randomUUID();
        Map<String, Object> source = Map.of("type", sourceType, "id", subject == null ? SYSTEM_ID : subject);
        String now = effectiveAt==null ? IdentityRepository.time(identities.now()) : effectiveAt;
        Map<String, Object> audit = fields("id", eventId, "producer", "G5", "source", source,
                "roomId", null, "scopeKind", "SYSTEM", "actorId", actor,
                "actorKind", actor == null ? "SYSTEM" : "USER", "action", action,
                "before", before, "after", after, "reason", reason, "result", result,
                "errorCode", code, "effectiveAt", effectiveAt==null?now:effectiveAt, "processedAt", now, "traceId", trace, "operationId", operationId);
        Map<String, Object> event = fields("eventId", eventId, "eventType", eventType, "schemaVersion", 1,
                "producer", "G5", "aggregate", source, "aggregateVersion", Long.toString(Math.max(1, version)),
                "roomId", null, "occurredAt", now, "actorId", actor,
                "actorKind", actor == null ? "SYSTEM" : "USER", "traceId", trace,
                "operationId", operationId, "audit", audit, "payload", payload);
        jdbc.update("INSERT INTO m2.g5_audit_event(event_id,event) VALUES (?,CAST(? AS jsonb))", eventId, json.writeValueAsString(event));
        jdbc.update("INSERT INTO m2.g5_outbox(event_id) VALUES (?)", eventId);
    }
    private static Map<String, Object> fields(Object... entries) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < entries.length; i += 2) { result.put((String) entries[i], entries[i + 1]); }
        return result;
    }
}
