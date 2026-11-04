package cn.edu.campus.approval.g5.application;

import java.util.*;
import org.springframework.stereotype.Service;
import cn.edu.campus.approval.g5.api.*;
import cn.edu.campus.approval.g5.api.BusinessFacts.*;
import cn.edu.campus.approval.g5.domain.*;
import cn.edu.campus.approval.g5.persistence.*;
import static cn.edu.campus.approval.g5.domain.BusinessInput.*;

/** G5 routing only. G6 authenticates accepted inbox events and performs delivery/current G1 reminder checks. */
@org.springframework.context.annotation.Profile("!shell")
@Service
public class NotificationRecipientsService {
    private final NotificationEventRules rules;
    private final RoomScopeClient rooms;
    private final AuthorizationRepository subjects;
    private final IdentityRepository clock;
    private final AuthorizationService transactions;
    public NotificationRecipientsService(NotificationEventRules rules,RoomScopeClient rooms,AuthorizationRepository subjects,IdentityRepository clock,AuthorizationService transactions){this.rules=rules;this.rooms=rooms;this.subjects=subjects;this.clock=clock;this.transactions=transactions;}
    public Recipients query(Map<String,Object> body,UUID trace){fields(body,Set.of("purpose","event"));if(!"NOTIFICATION_ROUTING".equals(body.get("purpose")))throw new IdentityException(403,"FORBIDDEN","不允许的通知用途");return resolve(rules.require(body.get("event")),trace);}
    public RecipientValidation validate(Map<String,Object> body,UUID trace){fields(body,Set.of("event","recipientId"));UUID target=id(body.get("recipientId"));Recipients all=resolve(rules.require(body.get("event")),trace);Recipient found=all.recipients().stream().filter(r->r.userId().equals(target)).findFirst().orElse(null);return new RecipientValidation(found!=null,found,all.resolvedAt());}
    private Recipients resolve(Map<String,Object> event,UUID trace){
        RoomScopeClient.outsideTransaction();String type=(String)event.get("eventType");var payload=object(event.get("payload"));
        // Fetch mandatory current room facts even when participants alone are already known.
        ScopeFacts facts=event.get("roomId")==null?null:rooms.notification(id(event.get("roomId")),type,id(event.get("eventId")),trace);
        Map<UUID,Set<String>> participants=new LinkedHashMap<>();Map<String,List<String>> cohorts=new LinkedHashMap<>();Set<UUID> excludedManagers=new HashSet<>();
        switch(type){
            case "ReservationReminderEarly","ReservationReminderLate","ReservationCancelled","AppealDecided","ViolationCorrected" -> participant(participants,payload.get("ownerId"),"OWNER");
            case "ApprovalStatusChanged" -> participant(participants,payload.get("applicantId"),"APPLICANT");
            case "ApprovalTaskOpened" -> {String branch=((Number)payload.get("level")).intValue()==1?"LEVEL1":"LEVEL2";cohorts.put(branch,List.of(branch.equals("LEVEL1")?"approval:level1":"approval:level2"));for(Object value:(List<?>)payload.get("excludedActorIds"))excludedManagers.add(id(value));excludedManagers.add(id(payload.get("applicantId")));}
            case "WorkOrderAssigned" -> {participant(participants,payload.get("newAssigneeId"),"NEW_ASSIGNEE");participant(participants,payload.get("oldAssigneeId"),"OLD_ASSIGNEE");}
            case "WorkOrderRepairStarted","WorkOrderAccepted" -> participant(participants,payload.get("reporterId"),"REPORTER");
            case "WorkOrderCompletionSubmitted" -> {participant(participants,payload.get("reporterId"),"REPORTER");cohorts.put("ROOM_MANAGER",List.of("workorder:accept"));for(Object value:(List<?>)payload.get("actualRepairerIds"))excludedManagers.add(id(value));}
            case "WorkOrderEscalated" -> {participant(participants,payload.get("assigneeId"),"ASSIGNEE");cohorts.put("ROOM_MANAGER",List.of("workorder:assign","workorder:read"));cohorts.put("OPERATOR",List.of("workorder:escalation-receive","workorder:read"));}
            case "WorkOrderReturned","WorkOrderClosed" -> {participant(participants,payload.get("reporterId"),"REPORTER");participant(participants,payload.get("assigneeId"),"ASSIGNEE");}
            case "RecoveryAttention" -> cohorts.put(facts==null?"SYSTEM_OPERATOR":"ROOM_MANAGER",List.of("operation:recover"));
            case "ClaimInvalidated","MaintenanceImpactApplied","ConfigChanged","AuditRecorded" -> { }
            default -> throw invalid();
        }
        Set<String> actions=new HashSet<>();cohorts.values().forEach(actions::addAll);
        return transactions.read(()->{
            List<Recipient> recipients=new ArrayList<>();List<Excluded> excluded=new ArrayList<>();
            for(var subject:subjects.notificationSubjects(participants.keySet(),actions)){
                Set<String> branches=new TreeSet<>(participants.getOrDefault(subject.id(),Set.of()));boolean evaded=false;
                for(var cohort:cohorts.entrySet()){
                    if(cohort.getValue().stream().allMatch(action->ScopePolicy.scoped(subject.grants(),action,facts))){
                        if(excludedManagers.contains(subject.id()))evaded=true;else branches.add(cohort.getKey());
                    }
                }
                if(branches.isEmpty()){if(evaded)excluded.add(new Excluded(subject.id(),"SCOPE_REVOKED"));continue;}
                if(!subject.enabled()){excluded.add(new Excluded(subject.id(),"ACCOUNT_DISABLED"));continue;}
                if(!validEmail(subject.email())){excluded.add(new Excluded(subject.id(),"NO_EMAIL"));continue;}
                recipients.add(new Recipient(subject.id(),subject.email(),subject.accountVersion(),subject.permissionVersion(),List.copyOf(branches)));
                if(recipients.size()>1000)throw new IdentityException(400,"VALIDATION_ERROR","合格通知接收人超过 1000，需管理员调整授权范围");
            }
            if(excluded.size()>1000)throw new IdentityException(400,"VALIDATION_ERROR","排除账户超过 1000，需管理员调整授权范围");
            recipients.sort(Comparator.comparing(r->r.userId().toString()));excluded.sort(Comparator.comparing(e->e.userId().toString()));
            return new Recipients(recipients.isEmpty()?"NO_ELIGIBLE_RECIPIENTS":"RESOLVED",List.copyOf(recipients),List.copyOf(excluded),facts,IdentityRepository.time(clock.now()));
        });
    }
    private boolean validEmail(String email){return email!=null&&email.length()<=254&&email.matches("[^\\s@<>]+@[^\\s@<>]+\\.[^\\s@<>]+");}
    private void participant(Map<UUID,Set<String>> participants,Object value,String branch){if(value!=null)participants.computeIfAbsent(id(value),ignored->new TreeSet<>()).add(branch);}
}
