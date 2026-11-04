package cn.edu.campus.reservation.g4.domain.model;

/**
 * 占用台账状态。
 *
 * <p>除 {@link #ACTIVE} 外都是终态墓碑：永久保留、不可删除、不可复活。
 * 晚到的 {@code ACTIVE} 只能得到 {@code SOURCE_TERMINAL}（技术 §7.2）。
 *
 * <p><b>没有 ABSENT</b>：ABSENT 只是「查询结果为空」的表达，不落库，
 * 因此不属于持久状态。
 */
public enum ClaimState {

    /** 占用生效中。 */
    ACTIVE,

    /** 由来源 owner 主动释放，必须带释放时刻。 */
    RELEASED,

    /** 被维护（停用/维修/注销）强制失效；不是来源方释放，不得冒充其版本。 */
    INVALIDATED,

    /** 裁决结果为拒绝（资源冲突、资格撤销）。 */
    REJECTED,

    /** 截止时未取得有效批准。 */
    EXPIRED;

    /** 是否为可删除/可复活的活跃状态。 */
    public boolean active() {
        return this == ACTIVE;
    }

    /** 是否为终态墓碑。 */
    public boolean terminal() {
        return this != ACTIVE;
    }
}
