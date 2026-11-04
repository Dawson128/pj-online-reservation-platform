package cn.edu.campus.reservation.g4.domain.model;

/**
 * 座位/房间不可用的原因。
 *
 * <p><b>枚举声明顺序即判定与输出顺序</b>，不得随意调整：
 * 房间有效 → 座位有效 → 访问院系 → 完整开放 → ADMIN → REPAIR → WHOLE → PERSONAL。
 * 依据技术文档 §7.2 的固定顺序。
 *
 * <p>多项原因可以同时成立并同时返回——不能用单个 {@code available} 布尔值代替。
 * 解除其中一项也只移除该项，不得覆盖其他项（需求 §7.3）。
 */
public enum UnavailableReason {

    /** 房间已停用（注销）。 */
    ROOM_DISABLED,

    /** 座位已停用（注销）。 */
    SEAT_DISABLED,

    /** 当前用户所属院系无权访问该房间。 */
    DEPARTMENT_RESTRICTED,

    /** 请求区间未完整落在开放时间内。 */
    CLOSED_HOURS,

    /** 管理员停用产生的限制（ADMIN 来源）。 */
    ADMIN_RESTRICTION,

    /** 维修封锁产生的限制（REPAIR 来源）。 */
    REPAIR_RESTRICTION,

    /** 整室批准占用（WHOLE 来源）。 */
    WHOLE_OCCUPIED,

    /** 他人或本人的个人预约占用（PERSONAL 来源）。 */
    PERSONAL_OCCUPIED
}
