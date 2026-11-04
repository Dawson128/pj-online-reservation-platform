package cn.edu.campus.reservation.g4.domain.model;

/**
 * 资源占用来源。
 *
 * <p>前四项参与资源占用；后三项是审计与配置来源，<b>不写资源台账</b>，
 * 因此资源判定只使用 {@link #resourceSource()} 覆盖的取值。
 *
 * <p>与课程任务书的对应关系：任务书要求 M1 座位状态记录三种写入来源
 * （M1 自身预约 / M2 审批占用 / M3 报修下架），对应 {@link #PERSONAL}、
 * {@link #WHOLE}、{@link #REPAIR}。第四类 {@link #ADMIN} 是任务书附件 1
 * 「自习室的登记、注销、可用性维护由管理员统一管理」的直接推论——
 * 管理员停用同样改变可用性，不记录来源就无法在解除时只解除本项限制。
 */
public enum SourceType {

    /** G1 个人预约，作用单个座位。 */
    PERSONAL(true),

    /** G2 整室批准，作用整间教室（含之后新增的座位）。 */
    WHOLE(true),

    /** G3 维修封锁，作用整间教室。 */
    REPAIR(true),

    /** G4 管理员停用，整间或单座。 */
    ADMIN(true),

    /** 账户类审计来源，不参与资源占用。 */
    ACCOUNT(false),

    /** 配置类审计来源，不参与资源占用。 */
    CONFIG(false),

    /** 系统任务来源，不参与资源占用。 */
    SYSTEM(false);

    private final boolean resourceSource;

    SourceType(boolean resourceSource) {
        this.resourceSource = resourceSource;
    }

    /** 是否参与资源占用判定。 */
    public boolean resourceSource() {
        return resourceSource;
    }

    /** 该来源是否作用于整间教室（必须 {@code seatId == null}）。 */
    public boolean wholeRoom() {
        return this == WHOLE || this == REPAIR;
    }
}
