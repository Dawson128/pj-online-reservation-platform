package cn.edu.campus.approval.g5.persistence;

import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** G5-owned delivery state only; lease claims and completion use short, separate transactions. */
@org.springframework.context.annotation.Profile("!shell")
@Repository
public class DeliveryRepository {
    private final JdbcTemplate jdbc;
    public DeliveryRepository(JdbcTemplate jdbc){this.jdbc=jdbc;}
    public record Row(UUID id,String sourceType,UUID sourceId,long sourceVersion,UUID actorId,UUID traceId,
        String status,long version,int attempts,OffsetDateTime nextRetryAt,boolean attention,String errorCode,
        OffsetDateTime createdAt,OffsetDateTime updatedAt,OffsetDateTime acceptedAt,OffsetDateTime occurredAt,
        OffsetDateTime deliveredAt,Boolean duplicate,UUID leaseToken,OffsetDateTime leaseUntil) { }
    private static final String SELECT="SELECT p.*,o.delivered_at,e.event->>'occurredAt' AS occurred_at FROM m2.g5_operation p JOIN m2.g5_outbox o ON o.event_id=p.outbox_event_id JOIN m2.g5_audit_event e ON e.event_id=p.outbox_event_id";
    private Row map(java.sql.ResultSet r,int n)throws java.sql.SQLException{
        return new Row(r.getObject("id",UUID.class),r.getString("source_type"),r.getObject("source_id",UUID.class),r.getLong("source_version"),r.getObject("actor_id",UUID.class),r.getObject("trace_id",UUID.class),r.getString("status"),r.getLong("version"),r.getInt("attempt_count"),r.getObject("next_retry_at",OffsetDateTime.class),r.getBoolean("needs_attention"),r.getString("error_code"),r.getObject("created_at",OffsetDateTime.class),r.getObject("updated_at",OffsetDateTime.class),r.getObject("accepted_at",OffsetDateTime.class),OffsetDateTime.parse(r.getString("occurred_at")),r.getObject("delivered_at",OffsetDateTime.class),r.getObject("duplicate_receipt",Boolean.class),r.getObject("lease_token",UUID.class),r.getObject("lease_until",OffsetDateTime.class));
    }
    public Row get(UUID id,boolean lock){var rows=jdbc.query(SELECT+" WHERE p.id=?"+(lock?" FOR UPDATE OF p":""),this::map,id);return rows.isEmpty()?null:rows.getFirst();}
    public List<Row> all(){return jdbc.query(SELECT+" ORDER BY p.created_at DESC,p.id",this::map);}
    public OffsetDateTime now(){return jdbc.queryForObject("SELECT clock_timestamp()",OffsetDateTime.class);}
    public record Claim(UUID id,UUID token,int sequence,String payload) { @Override public String toString(){return "Claim[id="+id+", payload=REDACTED]";} }
    public Claim claim(){
        var ids=jdbc.query("""
            SELECT id FROM m2.g5_operation
            WHERE (status IN ('PENDING','RETRY_WAIT') AND next_retry_at<=clock_timestamp())
               OR (status='RUNNING' AND lease_until<=clock_timestamp())
            ORDER BY created_at,id FOR UPDATE SKIP LOCKED LIMIT 1
            """,(r,n)->r.getObject(1,UUID.class));
        if(ids.isEmpty())return null;UUID id=ids.getFirst(),token=UUID.randomUUID();
        jdbc.update("UPDATE m2.g5_outbox_attempt SET finished_at=clock_timestamp(),outcome='UNKNOWN',error_code='LEASE_EXPIRED' WHERE operation_id=? AND finished_at IS NULL",id);
        int sequence=jdbc.queryForObject("""
            UPDATE m2.g5_operation SET status='RUNNING',lease_token=?,lease_until=clock_timestamp()+interval '30 seconds',
                version=version+1,attempt_count=attempt_count+1,next_retry_at=NULL,updated_at=clock_timestamp()
            WHERE id=? RETURNING attempt_count
            """,Integer.class,token,id);
        jdbc.update("UPDATE m2.g5_outbox SET attempts=? WHERE event_id=?",sequence,id);
        jdbc.update("INSERT INTO m2.g5_outbox_attempt(id,operation_id,sequence,lease_token) VALUES (?,?,?,?)",UUID.randomUUID(),id,sequence,token);
        String payload=jdbc.queryForObject("SELECT event::text FROM m2.g5_audit_event WHERE event_id=?",String.class,id);
        return new Claim(id,token,sequence,payload);
    }
    public boolean heartbeat(Claim claim){return jdbc.update("UPDATE m2.g5_operation SET lease_until=clock_timestamp()+interval '30 seconds' WHERE id=? AND status='RUNNING' AND lease_token=? AND lease_until>clock_timestamp()",claim.id(),claim.token())==1;}
    public boolean finish(Claim claim,String code,boolean permanent,Boolean duplicate,OffsetDateTime accepted){
        boolean success=accepted!=null;int backoff=switch(claim.sequence()){case 1->5;case 2->15;case 3->30;default->60;};
        int count=jdbc.update("""
            UPDATE m2.g5_operation SET status=?,version=version+1,lease_token=NULL,lease_until=NULL,
                next_retry_at=CASE WHEN ? THEN NULL ELSE clock_timestamp()+make_interval(secs=>?) END,
                first_failure_at=CASE WHEN ? THEN first_failure_at ELSE coalesce(first_failure_at,clock_timestamp()) END,
                error_code=?,duplicate_receipt=?,needs_attention=CASE WHEN ? THEN false ELSE (? OR needs_attention OR accepted_at<=clock_timestamp()-interval '5 minutes') END,
                updated_at=clock_timestamp()
            WHERE id=? AND status='RUNNING' AND lease_token=? AND lease_until>clock_timestamp()
            """,success?"SUCCEEDED":permanent?"BLOCKED":"RETRY_WAIT",success||permanent,backoff,success,code,duplicate,success,permanent,claim.id(),claim.token());
        // Record a received result even if the lease became stale; never overwrite the current operation.
        jdbc.update("UPDATE m2.g5_outbox_attempt SET finished_at=coalesce(finished_at,clock_timestamp()),outcome=CASE WHEN finished_at IS NULL THEN ? ELSE outcome END,error_code=CASE WHEN finished_at IS NULL THEN ? ELSE error_code END WHERE operation_id=? AND lease_token=?",
            success?"ACCEPTED":permanent?"REJECTED":"UNKNOWN",code,claim.id(),claim.token());
        if(count==1)jdbc.update("UPDATE m2.g5_outbox SET status=?,delivered_at=? WHERE event_id=?",success?"DELIVERED":permanent?"BLOCKED":"PENDING",accepted,claim.id());
        return count==1;
    }
    public void overdue(){jdbc.update("UPDATE m2.g5_operation SET needs_attention=true,version=version+1,updated_at=clock_timestamp() WHERE status<>'SUCCEEDED' AND NOT needs_attention AND accepted_at<=clock_timestamp()-interval '270 seconds'");}
    public void unconfigured(){
        jdbc.update("""
            UPDATE m2.g5_operation SET status='BLOCKED',error_code='DELIVERY_NOT_CONFIGURED',needs_attention=true,
                version=version+1,next_retry_at=NULL,lease_token=NULL,lease_until=NULL,updated_at=clock_timestamp()
            WHERE status IN ('PENDING','RETRY_WAIT') OR (status='RUNNING' AND lease_until<=clock_timestamp())
            """);
        jdbc.update("UPDATE m2.g5_outbox o SET status='BLOCKED' FROM m2.g5_operation p WHERE p.id=o.event_id AND p.status='BLOCKED' AND o.status<>'BLOCKED'");
    }
    public void requeue(UUID id){
        jdbc.update("UPDATE m2.g5_operation SET status='PENDING',version=version+1,next_retry_at=clock_timestamp(),error_code=NULL,lease_token=NULL,lease_until=NULL,updated_at=clock_timestamp() WHERE id=?",id);
        jdbc.update("UPDATE m2.g5_outbox SET status='PENDING' WHERE event_id=?",id);
    }
}
