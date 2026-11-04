package cn.edu.campus.approval.g5.domain;

import java.util.List;

/** The baseline's closed Action registry; custom roles compose these existing capabilities. */
public final class PermissionCatalog {
    public record Descriptor(String action, String description, List<String> scopeKinds, String ownerGroup) { }
    private static Descriptor business(String action,String description,String owner) {
        return new Descriptor(action,description,List.of("SCHOOL","DEPARTMENT","ROOM"),owner);
    }
    private static Descriptor operations(String action,String description) {
        return new Descriptor(action,description,List.of("ROOM","SYSTEM"),"G6");
    }
    public static final List<Descriptor> ALL=List.of(
        business("room:read","查看开放空间","G4"),
        business("room:manage","管理教室与座位","G4"),
        business("room:display","查看教室签到屏幕","G4"),
        business("room:apply","申请整间教室","G2"),
        business("resource:trace","追溯空间占用","G4"),
        business("reservation:create","预约个人座位","G1"),
        business("reservation:read-own","查看本人预约","G1"),
        business("reservation:cancel","取消本人预约","G1"),
        business("reservation:checkin","预约签到","G1"),
        business("violation:read-own","查看本人违约","G1"),
        business("violation:appeal","提交违约申诉","G1"),
        business("violation:read","查看违约记录","G1"),
        business("violation:review","处理违约申诉","G1"),
        business("violation:correct","更正违约记录","G1"),
        business("statistics:read","查看运营统计","G1"),
        business("application:read-own","查看本人整室申请","G2"),
        business("application:withdraw","撤销本人整室申请","G2"),
        business("application:read","查看授权范围内整室申请","G2"),
        business("application:terminate","终止整室申请","G2"),
        business("approval:level1","进行一级审批","G2"),
        business("approval:level2","进行二级审批","G2"),
        business("workorder:report","上报空间故障","G3"),
        business("workorder:read-own","查看本人报修","G3"),
        business("workorder:cancel","撤销本人报修","G3"),
        business("workorder:evaluate","评价本人报修","G3"),
        business("workorder:read","查看授权范围内工单","G3"),
        business("workorder:assign","分配维修工单","G3"),
        business("workorder:accept","验收维修结果","G3"),
        business("workorder:escalation-receive","接收工单升级提醒","G3"),
        business("workorder:read-assigned","查看本人维修工单","G3"),
        business("workorder:repair","处理维修工单","G3"),
        new Descriptor("system:manage","账户、角色与系统参数管理",List.of("SYSTEM"),"G5"),
        operations("audit:read","查看操作记录"),
        operations("operation:read","查看异常事项"),
        operations("operation:recover","恢复异常事项"),
        operations("notification:read","查看通知投递"),
        operations("notification:retry","重试通知投递"),
        operations("runtime:read","查看系统运行信息")
    );
    public static Descriptor require(String action) {
        return ALL.stream().filter(item->item.action().equals(action)).findFirst()
                .orElseThrow(()->Credentials.invalid("grants","包含未登记的操作权限"));
    }
    private PermissionCatalog() { }
}
