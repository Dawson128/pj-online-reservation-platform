package cn.edu.campus.reservation.g4.domain.model;

import java.util.Objects;
import java.util.UUID;

/**
 * 判定可用性时需要的一条占用台账快照。
 *
 * <p>只代表<b>已存在</b>的占用。终态（RELEASED/INVALIDATED/REJECTED/EXPIRED）
 * 不参与判定，调用方可以传入全部记录，由判定器按 {@link ClaimState#active()} 过滤；
 * 也可以在查询时只取 ACTIVE——两种都可，但不得把终态当作占用。
 *
 * @param sourceType 来源类型
 * @param seatId     作用座位；{@code null} 表示整间。PERSONAL 必须非空，
 *                   WHOLE/REPAIR 必须为空，ADMIN 两者皆可
 * @param interval   占用区间，半开。未释放的 REPAIR 用足够远的 end 表达"持续封锁"，
 *                   由调用方按台账的 end 是否为空决定上界
 * @param state      台账状态；终态不参与判定
 */
public record ClaimSnapshot(
        SourceType sourceType,
        UUID seatId,
        Interval interval,
        ClaimState state) {

    public ClaimSnapshot {
        Objects.requireNonNull(sourceType, "sourceType 不能为空");
        Objects.requireNonNull(interval, "interval 不能为空");
        Objects.requireNonNull(state, "state 不能为空");
        if (!sourceType.resourceSource()) {
            throw new IllegalArgumentException(sourceType + " 不参与资源占用，不应出现在台账判定中");
        }
        // 与数据库 CHECK resource_claim_scope_ck 保持一致的领域侧守卫
        if (sourceType == SourceType.PERSONAL && seatId == null) {
            throw new IllegalArgumentException("PERSONAL 来源必须带 seatId");
        }
        if (sourceType.wholeRoom() && seatId != null) {
            throw new IllegalArgumentException(sourceType + " 作用整间，seatId 必须为 null");
        }
    }

    /** 是否为整间占用。 */
    public boolean wholeRoom() {
        return seatId == null;
    }

    /** 该占用是否（在有效状态下）与给定区间相交。 */
    public boolean blocks(Interval window) {
        return state.active() && interval.overlaps(window);
    }
}
