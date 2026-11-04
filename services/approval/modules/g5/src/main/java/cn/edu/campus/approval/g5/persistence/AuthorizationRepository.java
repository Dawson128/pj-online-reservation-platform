package cn.edu.campus.approval.g5.persistence;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import cn.edu.campus.approval.g5.api.*;

/** Minimal facts, never reads password hashes. Inventories use two bounded-count SQL queries, no per-user SQL. */
@org.springframework.context.annotation.Profile("!shell")
@Repository
public class AuthorizationRepository {
    private final JdbcTemplate jdbc;
    private final IdentityRepository identities;
    public AuthorizationRepository(JdbcTemplate jdbc,IdentityRepository identities) {this.jdbc=jdbc;this.identities=identities;}
    public record Subject(UUID id,String displayName,UUID departmentId,boolean enabled,String accountVersion,
                          String permissionVersion,String email,List<Principal.Grant> grants) { }
    public Subject subject(UUID id) {
        var users=jdbc.query("SELECT id,display_name,department_id,enabled,version,permission_version FROM m2.account WHERE id=?",
            (rs,i)->new Subject(rs.getObject("id",UUID.class),rs.getString("display_name"),rs.getObject("department_id",UUID.class),rs.getBoolean("enabled"),rs.getString("version"),rs.getString("permission_version"),null,identities.grants(id)),id);
        if(users.isEmpty()) throw new IdentityException(404,"NOT_FOUND","账户不存在");return users.getFirst();
    }
    public List<Subject> candidates(String keyword) {
        String where="a.enabled AND EXISTS (SELECT 1 FROM m2.role_binding b JOIN m2.role_grant g ON g.role_id=b.role_id WHERE b.account_id=a.id AND g.action='workorder:repair')";
        var args=new ArrayList<Object>();
        if(keyword!=null){where+=" AND (position(lower(?) in lower(a.user_code))>0 OR position(lower(?) in lower(a.display_name))>0)";args.add(keyword);args.add(keyword);}
        return inventory(where,args,false);
    }
    public List<Subject> notificationSubjects(Set<UUID> participants,Set<String> actions) {
        if(participants.isEmpty()&&actions.isEmpty()) return List.of();
        String where="false";var args=new ArrayList<Object>();
        if(!participants.isEmpty()){where+=" OR a.id IN ("+placeholders(participants.size())+")";args.addAll(participants);}
        if(!actions.isEmpty()){where+=" OR EXISTS (SELECT 1 FROM m2.role_binding b JOIN m2.role_grant g ON g.role_id=b.role_id WHERE b.account_id=a.id AND g.action IN ("+placeholders(actions.size())+"))";args.addAll(actions);}
        var result=inventory("("+where+")",args,true);
        // An unknown mandatory participant is a missing dependency fact, never silently skipped.
        if(!result.stream().map(Subject::id).toList().containsAll(participants)) throw new IdentityException(503,"DEPENDENCY_UNAVAILABLE","通知参与人账户事实不完整");
        return result;
    }
    private List<Subject> inventory(String where,List<Object> args,boolean email) {
        String selection="SELECT a.id,a.display_name,a.department_id,a.enabled,a.version,a.permission_version"+(email?",a.email":"")+" FROM m2.account a WHERE "+where;
        Map<UUID,List<Principal.Grant>> grants=new HashMap<>();
        // Repeat the same selector under one repeatable-read snapshot instead of constructing a huge ID parameter list.
        jdbc.query("SELECT DISTINCT b.account_id,g.action,g.scope_kind,g.department_id,g.room_id FROM m2.role_binding b JOIN m2.role_grant g ON g.role_id=b.role_id WHERE b.account_id IN (SELECT a.id FROM m2.account a WHERE "+where+")",rs->{
            String kind=rs.getString("scope_kind");Map<String,Object> scope=switch(kind){
                case "ROOM"->Map.of("kind",kind,"roomId",rs.getObject("room_id",UUID.class));
                case "DEPARTMENT"->Map.of("kind",kind,"departmentId",rs.getObject("department_id",UUID.class));
                default->Map.of("kind",kind);
            };
            grants.computeIfAbsent(rs.getObject("account_id",UUID.class),ignored->new ArrayList<>()).add(new Principal.Grant(rs.getString("action"),scope));
        },args.toArray());
        return jdbc.query(selection+" ORDER BY a.created_at DESC,a.id ASC",(rs,i)->{
            UUID id=rs.getObject("id",UUID.class);return new Subject(id,rs.getString("display_name"),rs.getObject("department_id",UUID.class),rs.getBoolean("enabled"),rs.getString("version"),rs.getString("permission_version"),email?rs.getString("email"):null,List.copyOf(grants.getOrDefault(id,List.of())));
        },args.toArray());
    }
    private String placeholders(int count){return String.join(",",Collections.nCopies(count,"?"));}
}
