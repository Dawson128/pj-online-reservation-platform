package cn.edu.campus.reservation.g4.domain.model;

import java.util.Objects;
import java.util.UUID;

/**
 * 判定可用性时需要的房间快照。
 *
 * <p>刻意只带判定所需的字段，不引用持久化实体——可用性判定是纯函数，
 * 输入越小越容易测试，也避免领域层依赖 persistence。
 *
 * @param id           房间 ID
 * @param code         房间编号，仅用于日志与错误信息
 * @param enabled      是否有效；停止使用即注销，历史保留
 * @param schoolWide   是否全校开放；为 true 时 {@code departmentId} 必为 null
 * @param departmentId 归属院系；为 null 表示全校开放
 * @param capacity     教室容量，只用于整室申请人数校验，不限制座位数
 */
public record RoomSnapshot(
        UUID id,
        String code,
        boolean enabled,
        boolean schoolWide,
        UUID departmentId,
        int capacity) {

    public RoomSnapshot {
        Objects.requireNonNull(id, "id 不能为空");
        Objects.requireNonNull(code, "code 不能为空");
        if (schoolWide && departmentId != null) {
            throw new IllegalArgumentException("全校开放的房间不得指定归属院系");
        }
        if (!schoolWide && departmentId == null) {
            throw new IllegalArgumentException("院系专属房间必须指定归属院系");
        }
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity 必须大于 0，实际 " + capacity);
        }
    }

    /**
     * 普通访问判定：全校开放，或用户院系与房间归属一致。
     *
     * <p>注意这<b>只是访问资格</b>，不等于动作授权。动作授权（如 {@code room:apply}）
     * 由 G5 求值后以参数传入，本类不重复实现权限规则。
     */
    public boolean accessibleBy(UUID userDepartmentId) {
        return schoolWide || (departmentId != null && departmentId.equals(userDepartmentId));
    }
}
