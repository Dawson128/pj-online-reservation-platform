package cn.edu.campus.approval.g5.domain;

import java.util.*;
import cn.edu.campus.approval.g5.api.Principal;
import cn.edu.campus.approval.g5.api.BusinessFacts.ScopeFacts;

/** Scope matching never infers powers from role names, enabled rooms, or system administration. */
public final class ScopePolicy {
    private static final Set<String> DISCOVERY=Set.of("room:read","reservation:create","room:apply","workorder:report");
    public static boolean accessible(UUID department,ScopeFacts room) {return room.schoolWide()||Objects.equals(department,room.deptId());}
    public static boolean has(List<Principal.Grant> grants,String action) {return grants.stream().anyMatch(g->g.action().equals(action));}
    public static boolean matches(Map<String,Object> scope,ScopeFacts room) {
        String kind=(String)scope.get("kind");
        if(room==null) return "SYSTEM".equals(kind);
        return switch(kind) {
            case "SCHOOL" -> true;
            case "DEPARTMENT" -> !room.schoolWide()&&same(scope.get("departmentId"),room.deptId());
            case "ROOM" -> same(scope.get("roomId"),room.roomId());
            default -> false;
        };
    }
    private static boolean same(Object value,UUID id) {return value!=null&&id!=null&&value.toString().equals(id.toString());}
    public static boolean scoped(List<Principal.Grant> grants,String action,ScopeFacts room) {return grants.stream().anyMatch(g->g.action().equals(action)&&matches(g.scope(),room));}
    public static boolean allowed(List<Principal.Grant> grants,UUID dept,String action,ScopeFacts room) {
        return scoped(grants,action,room)&&(room==null||!DISCOVERY.contains(action)||accessible(dept,room));
    }
    private ScopePolicy() { }
}
