package cn.edu.campus.approval.g5.application;

import java.util.*;
import java.time.*;
import java.nio.charset.StandardCharsets;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Service;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import cn.edu.campus.approval.g5.api.*;
import cn.edu.campus.approval.g5.api.OperationFacts.*;
import cn.edu.campus.approval.g5.persistence.*;
import cn.edu.campus.approval.g5.persistence.AuthorizationRepository.Subject;
import cn.edu.campus.approval.g5.persistence.DeliveryRepository.Row;
import cn.edu.campus.approval.g5.domain.*;
import static cn.edu.campus.approval.g5.domain.BusinessInput.*;

@org.springframework.context.annotation.Profile("!shell")
@Service
public class OperationService implements OperationReadPort,OperationRecoveryPort {
    private final DeliveryRepository deliveries;private final AuthorizationRepository accounts;private final ManagementRepository management;
    private final AuthAudit audit;private final ObjectMapper json;private final Environment environment;private final TransactionTemplate read,write;
    public OperationService(DeliveryRepository deliveries,AuthorizationRepository accounts,ManagementRepository management,AuthAudit audit,ObjectMapper json,Environment environment,TransactionTemplate transaction){
        this.deliveries=deliveries;this.accounts=accounts;this.management=management;this.audit=audit;this.json=json;this.environment=environment;write=new TransactionTemplate(transaction.getTransactionManager());read=new TransactionTemplate(transaction.getTransactionManager());read.setReadOnly(true);read.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }
    private <T>T snapshot(java.util.function.Supplier<T> body){try{return read.execute(s->body.get());}catch(DataAccessException|TransactionException failure){throw unavailable();}}
    private Subject current(Principal supplied){
        if(supplied==null)throw Credentials.unauthenticated();
        try{if(!OffsetDateTime.parse(supplied.expiresAt()).isAfter(deliveries.now()))throw Credentials.unauthenticated();}catch(java.time.format.DateTimeParseException|NullPointerException invalid){throw Credentials.unauthenticated();}Subject now;
        try{now=accounts.subject(supplied.userId());}catch(IdentityException absent){if(absent.status()==404)throw Credentials.unauthenticated();throw absent;}
        if(!now.enabled())throw Credentials.unauthenticated();
        if(!now.accountVersion().equals(supplied.accountVersion())||!now.permissionVersion().equals(supplied.permissionVersion()))throw new IdentityException(409,"VERSION_CONFLICT","当前身份权限已变化，请重新核验");return now;
    }
    private boolean has(Subject subject,String action){return ScopePolicy.scoped(subject.grants(),action,null);}
    private boolean readable(Subject subject,Row row){return row!=null&&(has(subject,"operation:read")||subject.id().equals(row.actorId()));}
    private void roomFilter(Subject subject,UUID room){if(room!=null&&!subject.grants().stream().anyMatch(g->g.action().equals("operation:read")&&"ROOM".equals(g.scope().get("kind"))&&room.toString().equals(g.scope().get("roomId").toString())))throw forbidden();}
    private Row visible(Subject subject,UUID id){Row row=deliveries.get(id,false);if(!readable(subject,row))throw missing();return row;}
    private Operation dto(Subject subject,Row row){
        List<String> actions=has(subject,"operation:read")&&has(subject,"operation:recover")&&Set.of("BLOCKED","RETRY_WAIT").contains(row.status())?List.of("operation:recover"):List.of();
        Ref source=new Ref(row.sourceType(),row.sourceId());Result result=row.status().equals("SUCCEEDED")?new Result(Boolean.TRUE.equals(row.duplicate())?"ALREADY_APPLIED":"APPLIED",source,Long.toString(row.sourceVersion()),time(row.deliveredAt())):null;
        return new Operation(row.id(),Long.toString(row.version()),time(row.createdAt()),time(row.updatedAt()),"G5",source,null,"SYSTEM","OTHER",null,row.actorId(),row.id(),row.status(),"NONE",time(row.acceptedAt()),row.attempts(),time(row.nextRetryAt()),error(row),row.attention(),result,"/api/m2/operations/"+row.id(),actions);
    }
    private AuthResponses.Error error(Row row){return row.errorCode()==null?null:new AuthResponses.Error("DEPENDENCY_UNAVAILABLE",switch(row.errorCode()){
        case "DELIVERY_NOT_CONFIGURED"->"G6 投递地址或服务凭据尚未配置，需管理员配置后重排";
        case "REMOTE_REJECTED"->"G6 拒绝原事件，请核对服务身份或事件契约后重排";
        default->"G6 尚未确认受理，保留原事件重试；持续失败需人工处理";
    },row.traceId(),new AuthResponses.Details(List.of(),List.of(),null,row.id(),null));}
    @Override public Operation get(Principal principal,UUID id){return snapshot(()->{Subject actor=current(principal);return dto(actor,visible(actor,Objects.requireNonNull(id)));});}
    private record Filter(int page,int size,String status,Boolean attention,OffsetDateTime from,OffsetDateTime to,String sourceType,UUID sourceId,UUID room,String category){ }
    private Filter filter(Map<String,Object> query){
        fields(query,Set.of(),"page","pageSize","status","needsAttention","createdFrom","createdTo","sourceType","sourceId","roomId","ownerGroup","category");
        int page=query.containsKey("page")?integer(query.get("page"),1,1000000):1,size=query.containsKey("pageSize")?integer(query.get("pageSize"),1,100):20;
        String status=optional(query,"status",Set.of("PENDING","RUNNING","RETRY_WAIT","SUCCEEDED","BLOCKED"));String type=optional(query,"sourceType",Set.of("PERSONAL","WHOLE","REPAIR","ADMIN","ACCOUNT","CONFIG","SYSTEM"));String category=optional(query,"category",Set.of("RESOURCE","NOTIFICATION_ROUTE","OTHER"));
        if(query.containsKey("ownerGroup")&&!"G5".equals(query.get("ownerGroup")))throw invalid();
        Boolean attention=null;if(query.containsKey("needsAttention")){if(!(query.get("needsAttention") instanceof Boolean b))throw invalid();attention=b;}
        OffsetDateTime from=null,to=null;if(query.containsKey("createdFrom")||query.containsKey("createdTo")){try{from=OffsetDateTime.parse((String)query.get("createdFrom"));to=OffsetDateTime.parse((String)query.get("createdTo"));}catch(RuntimeException bad){throw invalid();}if(!from.isBefore(to)||Duration.between(from,to).compareTo(Duration.ofDays(31))>0)throw invalid();}
        return new Filter(page,size,status,attention,from,to,type,query.containsKey("sourceId")?id(query.get("sourceId")):null,query.containsKey("roomId")?id(query.get("roomId")):null,category);
    }
    private String optional(Map<String,Object> map,String key,Set<String> allowed){if(!map.containsKey(key))return null;String value=text(map.get(key),40);if(!allowed.contains(value))throw invalid();return value;}
    @Override public Page list(Principal principal,Map<String,Object> query){return snapshot(()->{
        Subject actor=current(principal);Filter f=filter(query);roomFilter(actor,f.room());if(!has(actor,"operation:read")&&f.room()==null)throw forbidden();OffsetDateTime now=deliveries.now(),from=f.from()==null?now.minusDays(7):f.from(),to=f.to()==null?now:f.to();
        var rows=f.room()!=null||!has(actor,"operation:read")?List.<Row>of():deliveries.all().stream().filter(row->!row.createdAt().isBefore(from)&&row.createdAt().isBefore(to)&&(f.status()==null||f.status().equals(row.status()))&&(f.attention()==null||f.attention()==row.attention())&&(f.sourceType()==null||f.sourceType().equals(row.sourceType()))&&(f.sourceId()==null||f.sourceId().equals(row.sourceId()))&&(f.category()==null||f.category().equals("OTHER"))).toList();
        int start=Math.min(rows.size(),(f.page()-1)*f.size()),end=Math.min(rows.size(),start+f.size());return new Page(rows.subList(start,end).stream().map(row->dto(actor,row)).toList(),f.page(),f.size(),rows.size(),time(now));
    });}
    @Override public Metrics metrics(Principal principal,UUID room){return snapshot(()->{
        Subject actor=current(principal);roomFilter(actor,room);if(room==null&&!has(actor,"operation:read"))throw forbidden();OffsetDateTime now=deliveries.now();var rows=room==null&&has(actor,"operation:read")?deliveries.all().stream().filter(row->!row.status().equals("SUCCEEDED")).toList():List.<Row>of();
        Row oldest=rows.stream().min(Comparator.comparing(Row::occurredAt).thenComparing(row->row.id().toString())).orElse(null);
        // G5 operation acceptance equals original outbox creation. Choose between both contractual clocks.
        record Pending(String kind,UUID id,OffsetDateTime since){}var pending=new ArrayList<Pending>();for(Row row:rows){pending.add(new Pending("OPERATION",row.id(),row.acceptedAt()));pending.add(new Pending("OUTBOX",row.id(),row.occurredAt()));}
        Pending first=pending.stream().min(Comparator.comparing(Pending::since).thenComparing(Pending::kind).thenComparing(p->p.id().toString())).orElse(null);
        PendingRef ref=first==null?null:new PendingRef(first.kind(),first.id(),time(first.since()),first.kind().equals("OUTBOX")?"/api/m3/admin/outbox-references/"+first.id()+"?module=m2&ownerGroup=G5":"/api/m2/operations/"+first.id());
        return new Metrics(time(now),new Counters(rows.size(),(int)rows.stream().filter(Row::attention).count(),rows.size(),oldest==null?null:age(oldest.occurredAt(),now),first==null?null:age(first.since(),now),ref),null);
    });}
    public OutboxReference outbox(Principal principal,UUID id){return snapshot(()->{Subject actor=current(principal);Row row=deliveries.get(id,false);if(row==null||!has(actor,"operation:read"))throw missing();return new OutboxReference(row.id(),row.id(),new Ref(row.sourceType(),row.sourceId()),null,"G5","G6",time(row.occurredAt()),time(row.deliveredAt()),row.attempts(),time(row.nextRetryAt()),error(row));});}
    @Override public Operation retry(Principal principal,UUID id,Map<String,Object> body,UUID key){
        snapshot(()->current(principal));fields(body,Set.of("expectedVersion","reason"));String expected=version(body.get("expectedVersion")),reason=text(body.get("reason"),1000);if(key==null||id==null)throw invalid();String path="/api/m2/admin/operations/"+id+"/retry",digest=digest(expected,reason);
        try{return write.execute(status->{
            Subject actor=current(principal);Row row=deliveries.get(id,true);if(!readable(actor,row))throw missing();if(!has(actor,"operation:read")||!has(actor,"operation:recover"))throw forbidden();
            var prior=management.cached(actor.id(),"POST",path,key);if(prior!=null){if(!Credentials.same(digest,prior.digest()))throw new IdentityException(409,"IDEMPOTENCY_CONFLICT","同一请求键不能更改重试正文");return prior.live()?json.readValue(prior.response(),Operation.class):dto(actor,row);}
            if(!Long.toString(row.version()).equals(expected))throw new IdentityException(409,"VERSION_CONFLICT","操作状态已变化，请刷新",null,Long.toString(row.version()),null);
            if(!Set.of("BLOCKED","RETRY_WAIT").contains(row.status()))throw new IdentityException(409,"STATE_CONFLICT","只有失败待重试或阻塞的原操作可重新排队");
            deliveries.requeue(id);audit.recovery(actor.id(),id,UUID.randomUUID(),reason);Operation result=dto(actor,deliveries.get(id,false));management.remember(actor.id(),"POST",path,key,digest,json.writeValueAsString(result),200,null,null);return result;
        });}catch(IdentityException failure){
            try{write.executeWithoutResult(status->{UUID actor=null;try{actor=current(principal).id();}catch(IdentityException ignored){}audit.recoveryRejected(actor,id,UUID.randomUUID(),failure.code());});}
            catch(DataAccessException|TransactionException auditFailure){throw unavailable();}throw failure;
        }catch(DataAccessException|TransactionException failure){throw unavailable();}
    }
    private String digest(String version,String reason){String secret=environment.getProperty("G5_IDEMPOTENCY_HMAC_KEY");if(secret==null||secret.length()<32)throw unavailable();try{Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));return HexFormat.of().formatHex(mac.doFinal(json.writeValueAsBytes(new TreeMap<>(Map.of("expectedVersion",version,"reason",reason)))));}catch(java.security.GeneralSecurityException failure){throw unavailable();}}
    private int age(OffsetDateTime since,OffsetDateTime now){return (int)Math.min(Integer.MAX_VALUE,Math.max(0,Duration.between(since,now).getSeconds()));}
    private String time(OffsetDateTime time){return time==null?null:IdentityRepository.time(time);}
    private IdentityException forbidden(){return new IdentityException(403,"FORBIDDEN","需要当前范围的异常操作权限");}
    private IdentityException missing(){return new IdentityException(404,"NOT_FOUND","操作不存在或不可见");}
    private IdentityException unavailable(){return new IdentityException(503,"DEPENDENCY_UNAVAILABLE","G5 操作状态暂不可用");}
}
