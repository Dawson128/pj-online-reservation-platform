package cn.edu.campus.approval.g5.application;

import java.util.*;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import cn.edu.campus.approval.g5.api.*;
import cn.edu.campus.approval.g5.api.BusinessFacts.*;
import cn.edu.campus.approval.g5.domain.*;
import cn.edu.campus.approval.g5.persistence.*;
import cn.edu.campus.approval.g5.persistence.AuthorizationRepository.Subject;
import static cn.edu.campus.approval.g5.domain.BusinessInput.*;

@org.springframework.context.annotation.Profile("!shell")
@Service
public class AuthorizationService implements AuthorizationPort {
    private final IdentityService identities;
    private final IdentityRepository clock;
    private final AuthorizationRepository subjects;
    private final RoomScopeClient rooms;
    private final TransactionTemplate snapshot;
    public AuthorizationService(IdentityService identities,IdentityRepository clock,AuthorizationRepository subjects,RoomScopeClient rooms,TransactionTemplate transaction){
        this.identities=identities;this.clock=clock;this.subjects=subjects;this.rooms=rooms;
        snapshot=new TransactionTemplate(transaction.getTransactionManager());snapshot.setReadOnly(true);snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }
    <T>T read(Supplier<T> action){try{return snapshot.execute(status->action.get());}catch(DataAccessException|TransactionException failure){throw new IdentityException(503,"DEPENDENCY_UNAVAILABLE","身份权限事实暂不可用");}}
    private Principal actor(String session,UUID actor){Principal principal=identities.introspect(session);if(!principal.userId().equals(actor))throw forbidden();return principal;}
    private void versions(Principal principal,String account,String permission){if(!principal.accountVersion().equals(account)||!principal.permissionVersion().equals(permission))throw new IdentityException(409,"VERSION_CONFLICT","账户或权限版本已变化");}
    @Override public AuthorizationBatch check(String session,Map<String,Object> body,UUID trace){
        RoomScopeClient.outsideTransaction();fields(body,Set.of("actorUserId","accountVersion","permissionVersion","checks"));
        UUID actor=id(body.get("actorUserId"));String account=version(body.get("accountVersion")),permission=version(body.get("permissionVersion"));
        Principal first=actor(session,actor);versions(first,account,permission);
        List<Map<String,Object>> checks=new ArrayList<>();Set<UUID> ids=new HashSet<>(),roomIds=new LinkedHashSet<>();
        for(Object value:list(body.get("checks"),1,100)){
            var check=object(value);fields(check,Set.of("checkId","action","target"));if(!ids.add(id(check.get("checkId"))))throw invalid();
            PermissionCatalog.require(text(check.get("action"),80));var target=object(check.get("target"));
            if("ROOM".equals(target.get("kind"))){fields(target,Set.of("kind","roomId"));roomIds.add(id(target.get("roomId")));}
            else if("SYSTEM".equals(target.get("kind")))fields(target,Set.of("kind"));else throw invalid();checks.add(check);
        }
        Map<UUID,ScopeFacts> facts=rooms.batch(roomIds,trace);
        return read(()->{
            Principal current=actor(session,actor);versions(current,account,permission);var now=clock.now();List<Decision> decisions=new ArrayList<>();
            for(var check:checks){var target=object(check.get("target"));boolean room="ROOM".equals(target.get("kind"));ScopeFacts fact=room?facts.get(id(target.get("roomId"))):null;String action=(String)check.get("action");
                boolean allowed=(!room||fact!=null)&&ScopePolicy.allowed(current.grants(),current.deptId(),action,fact);
                String reason=room&&fact==null?"RESOURCE_NOT_FOUND":!ScopePolicy.has(current.grants(),action)?"NO_ACTION":allowed?"ALLOWED":"SCOPE_MISMATCH";
                decisions.add(new Decision(id(check.get("checkId")),action,Map.copyOf(target),allowed,fact==null?null:fact.roomVersion(),reason));
            }
            return new AuthorizationBatch(actor,account,permission,IdentityRepository.time(now),IdentityRepository.time(now.plusSeconds(5)),List.copyOf(decisions));
        });
    }
    public Eligibility qualification(String caller,Map<String,Object> body,UUID trace){
        RoomScopeClient.outsideTransaction();fields(body,Set.of("subjectId","roomId","action","source","operationId","purpose"));
        UUID subject=id(body.get("subjectId")),room=id(body.get("roomId"));id(body.get("operationId"));var source=object(body.get("source"));fields(source,Set.of("type","id"));id(source.get("id"));
        boolean repair="REPAIR_START".equals(body.get("purpose"));
        if(repair){if(!caller.equals("G3")||!"workorder:repair".equals(body.get("action"))||!"REPAIR".equals(source.get("type")))throw invalid();}
        else if(!"FINAL_APPROVAL".equals(body.get("purpose"))||!Set.of("G2","G4").contains(caller)||!"room:apply".equals(body.get("action"))||!"WHOLE".equals(source.get("type")))throw invalid();
        ScopeFacts facts=rooms.authorization(room,trace);return read(()->eligibility(subjects.subject(subject),facts,repair));
    }
    private Eligibility eligibility(Subject subject,ScopeFacts room,boolean repair){
        String action=repair?"workorder:repair":"room:apply";boolean has=ScopePolicy.has(subject.grants(),action),scope=ScopePolicy.scoped(subject.grants(),action,room),access=ScopePolicy.accessible(subject.departmentId(),room);var now=clock.now();
        return new Eligibility(subject.id(),subject.enabled(),has,scope,access,subject.enabled()&&has&&scope&&(repair||access),subject.accountVersion(),subject.permissionVersion(),room.roomVersion(),IdentityRepository.time(now),IdentityRepository.time(now.plusSeconds(5)));
    }
    private void assigner(String session,UUID actor,ScopeFacts facts){Principal current=actor(session,actor);if(!ScopePolicy.allowed(current.grants(),current.deptId(),"workorder:assign",facts))throw forbidden();}
    public CandidatePage candidates(String session,Map<String,Object> body,UUID trace){
        RoomScopeClient.outsideTransaction();fields(body,Set.of("actorUserId","roomId"),"page","pageSize","keyword");UUID actor=id(body.get("actorUserId")),room=id(body.get("roomId"));actor(session,actor);
        int page=body.containsKey("page")?integer(body.get("page"),1,1000000):1,size=body.containsKey("pageSize")?integer(body.get("pageSize"),1,100):20;
        String keyword=body.containsKey("keyword")?text(body.get("keyword"),100):null;
        ScopeFacts facts=rooms.authorization(room,trace);return read(()->{
            assigner(session,actor,facts);var eligible=subjects.candidates(keyword).stream().filter(s->ScopePolicy.scoped(s.grants(),"workorder:repair",facts)).toList();
            int from=Math.min(eligible.size(),(page-1)*size),to=Math.min(eligible.size(),from+size);var now=clock.now();
            return new CandidatePage(eligible.subList(from,to).stream().map(s->new Candidate(s.id(),s.displayName(),"账户有效，具备该房间维修权限")).toList(),page,size,eligible.size(),IdentityRepository.time(now));
        });
    }
    public Eligibility assignee(String session,Map<String,Object> body,UUID trace){
        RoomScopeClient.outsideTransaction();fields(body,Set.of("actorUserId","assigneeId","roomId"));UUID actor=id(body.get("actorUserId")),target=id(body.get("assigneeId")),room=id(body.get("roomId"));actor(session,actor);
        ScopeFacts facts=rooms.authorization(room,trace);return read(()->{assigner(session,actor,facts);return eligibility(subjects.subject(target),facts,true);});
    }
    private IdentityException forbidden(){return new IdentityException(403,"FORBIDDEN","当前操作者无权执行此操作");}
}
