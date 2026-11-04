package cn.edu.campus.reservation.g4.domain.service;

import cn.edu.campus.reservation.g4.domain.model.AvailabilityResult;
import cn.edu.campus.reservation.g4.domain.model.ClaimSnapshot;
import cn.edu.campus.reservation.g4.domain.model.Interval;
import cn.edu.campus.reservation.g4.domain.model.OpeningCalendar;
import cn.edu.campus.reservation.g4.domain.model.RoomSnapshot;
import cn.edu.campus.reservation.g4.domain.model.SeatSnapshot;
import cn.edu.campus.reservation.g4.domain.model.SourceType;
import cn.edu.campus.reservation.g4.domain.model.UnavailableReason;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 可用性判定器：系统内唯一的"这个座位这个时段能不能用、为什么不能用"的实现。
 *
 * <p>纯函数，无状态、无数据库、无 Spring 依赖，便于单测覆盖全部边界。
 *
 * <p>判定顺序固定（技术 §7.2），顺序即 {@link UnavailableReason} 的声明顺序：
 * 房间有效 → 访问院系 → 完整开放 → ADMIN → REPAIR → WHOLE → 座位有效 → PERSONAL。
 * 任一命中即不可用，<b>可以同时命中多项并全部返回</b>。
 *
 * <p>关键规则（需求 §7.3）：
 * <ul>
 *   <li>不同来源独立叠加，解除一项<b>不得</b>覆盖其他项，因此本类不做"短路"——
 *       不会因为发现 ADMIN 就跳过检查 REPAIR。</li>
 *   <li>整间占用（WHOLE/REPAIR）作用于该室全部有效座位，包括之后新增的座位。</li>
 *   <li>可预约数按实际限制计算，等于"无任何原因的座位数"。</li>
 * </ul>
 */
public final class AvailabilityEvaluator {

    /**
     * 判定一次可用性。
     *
     * @param room            房间快照
     * @param seats           该房间的座位快照集合（含停用座位，由本类负责标记）
     * @param calendar        开放时间表
     * @param claims          该房间的占用台账快照；可包含终态记录，本类按状态过滤
     * @param window          待判定的区间，半开
     * @param userDepartmentId 当前用户所属院系；全校开放房间可为 null
     * @return 判定结果，永不为 null
     */
    public AvailabilityResult evaluate(
            RoomSnapshot room,
            List<SeatSnapshot> seats,
            OpeningCalendar calendar,
            List<ClaimSnapshot> claims,
            Interval window,
            UUID userDepartmentId) {

        Objects.requireNonNull(room, "room 不能为空");
        Objects.requireNonNull(seats, "seats 不能为空");
        Objects.requireNonNull(calendar, "calendar 不能为空");
        Objects.requireNonNull(claims, "claims 不能为空");
        Objects.requireNonNull(window, "window 不能为空");

        List<UnavailableReason> roomReasons = roomLevelReasons(room, calendar, claims, window, userDepartmentId);

        List<AvailabilityResult.SeatResult> seatResults = new ArrayList<>(seats.size());
        int availableSeatCount = 0;
        for (SeatSnapshot seat : seats) {
            EnumSet<UnavailableReason> reasons = EnumSet.noneOf(UnavailableReason.class);
            reasons.addAll(roomReasons);
            collectSeatReasons(reasons, seat, claims, window);
            // EnumSet 按 ordinal 迭代，天然满足固定输出顺序
            AvailabilityResult.SeatResult result =
                    new AvailabilityResult.SeatResult(seat.id(), List.copyOf(reasons));
            seatResults.add(result);
            if (result.available()) {
                availableSeatCount++;
            }
        }

        return new AvailabilityResult(availableSeatCount, roomReasons, seatResults);
    }

    /** 房间级原因：与具体座位无关的部分。 */
    private List<UnavailableReason> roomLevelReasons(
            RoomSnapshot room,
            OpeningCalendar calendar,
            List<ClaimSnapshot> claims,
            Interval window,
            UUID userDepartmentId) {

        EnumSet<UnavailableReason> reasons = EnumSet.noneOf(UnavailableReason.class);

        if (!room.enabled()) {
            reasons.add(UnavailableReason.ROOM_DISABLED);
        }
        if (!room.accessibleBy(userDepartmentId)) {
            reasons.add(UnavailableReason.DEPARTMENT_RESTRICTED);
        }
        if (!calendar.fullyOpen(window)) {
            reasons.add(UnavailableReason.CLOSED_HOURS);
        }
        if (hasBlockingClaim(claims, window, SourceType.ADMIN, null)) {
            reasons.add(UnavailableReason.ADMIN_RESTRICTION);
        }
        if (hasBlockingClaim(claims, window, SourceType.REPAIR, null)) {
            reasons.add(UnavailableReason.REPAIR_RESTRICTION);
        }
        if (hasBlockingClaim(claims, window, SourceType.WHOLE, null)) {
            reasons.add(UnavailableReason.WHOLE_OCCUPIED);
        }

        return List.copyOf(reasons);
    }

    /** 座位级原因：在本座位生效的额外限制。 */
    private void collectSeatReasons(
            EnumSet<UnavailableReason> reasons,
            SeatSnapshot seat,
            List<ClaimSnapshot> claims,
            Interval window) {

        if (!seat.enabled()) {
            reasons.add(UnavailableReason.SEAT_DISABLED);
        }
        // 单座停用：ADMIN 且 seatId 命中本座
        if (hasBlockingClaim(claims, window, SourceType.ADMIN, seat.id())) {
            reasons.add(UnavailableReason.ADMIN_RESTRICTION);
        }
        // 个人占用：PERSONAL 且 seatId 命中本座
        if (hasBlockingClaim(claims, window, SourceType.PERSONAL, seat.id())) {
            reasons.add(UnavailableReason.PERSONAL_OCCUPIED);
        }
    }

    /**
     * 是否存在生效且与窗口相交的指定来源占用。
     *
     * @param seatId 期望匹配的座位 ID；传 {@code null} 表示只要整间占用（seatId 为 null 的记录）
     */
    private boolean hasBlockingClaim(
            List<ClaimSnapshot> claims,
            Interval window,
            SourceType sourceType,
            UUID seatId) {

        for (ClaimSnapshot claim : claims) {
            if (claim.sourceType() != sourceType) {
                continue;
            }
            if (!Objects.equals(claim.seatId(), seatId)) {
                continue;
            }
            if (claim.blocks(window)) {
                return true;
            }
        }
        return false;
    }
}
