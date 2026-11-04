package cn.edu.campus.reservation.g4.domain;

import cn.edu.campus.reservation.g4.domain.model.AvailabilityResult;
import cn.edu.campus.reservation.g4.domain.model.ClaimSnapshot;
import cn.edu.campus.reservation.g4.domain.model.ClaimState;
import cn.edu.campus.reservation.g4.domain.model.Interval;
import cn.edu.campus.reservation.g4.domain.model.OpeningCalendar;
import cn.edu.campus.reservation.g4.domain.model.OpeningCalendar.WeeklyRule;
import cn.edu.campus.reservation.g4.domain.model.RoomSnapshot;
import cn.edu.campus.reservation.g4.domain.model.SeatSnapshot;
import cn.edu.campus.reservation.g4.domain.model.SourceType;
import cn.edu.campus.reservation.g4.domain.model.UnavailableReason;
import cn.edu.campus.reservation.g4.domain.service.AvailabilityEvaluator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 可用性判定：覆盖原因顺序、来源叠加、整间与单座的区别、终态不阻塞、相邻不算冲突。
 *
 * <p>这些用例对应需求 §7.3「不同来源独立存在，解除一项不能覆盖其他项」
 * 与技术 §7.2 的固定判定顺序。
 */
class AvailabilityEvaluatorTest {

    private static final LocalDate DAY = LocalDate.of(2026, 11, 4);

    private static final UUID ROOM_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID SEAT_A = UUID.fromString("33333333-3333-4333-8333-333333333331");
    private static final UUID SEAT_B = UUID.fromString("33333333-3333-4333-8333-333333333332");
    private static final UUID SEAT_C = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID DEPT_ONE = UUID.fromString("99999999-9999-4999-8999-999999999991");

    private final AvailabilityEvaluator evaluator = new AvailabilityEvaluator();

    private static Instant at(int hour) {
        return DAY.atTime(hour, 0).atZone(OpeningCalendar.ZONE).toInstant();
    }

    private static Interval slot(int fromHour, int toHour) {
        return Interval.of(at(fromHour), at(toHour));
    }

    /** 每日 08:00-22:00 开放。 */
    private static OpeningCalendar alwaysOpen() {
        List<WeeklyRule> rules = new ArrayList<>();
        for (int weekday = 1; weekday <= 7; weekday++) {
            rules.add(new WeeklyRule(weekday, 8 * 60, 22 * 60));
        }
        return new OpeningCalendar(rules, Map.of());
    }

    private static RoomSnapshot openRoom() {
        return new RoomSnapshot(ROOM_ID, "R001", true, true, null, 4);
    }

    private static List<SeatSnapshot> seats() {
        return List.of(
                new SeatSnapshot(SEAT_A, "A01", true),
                new SeatSnapshot(SEAT_B, "B01", true),
                new SeatSnapshot(SEAT_C, "C01", false));
    }

    private static ClaimSnapshot whole(ClaimState state, Interval interval) {
        return new ClaimSnapshot(SourceType.WHOLE, null, interval, state);
    }

    private static ClaimSnapshot adminAt(UUID seatId, Interval interval) {
        return new ClaimSnapshot(SourceType.ADMIN, seatId, interval, ClaimState.ACTIVE);
    }

    private static ClaimSnapshot personalAt(UUID seatId, Interval interval) {
        return new ClaimSnapshot(SourceType.PERSONAL, seatId, interval, ClaimState.ACTIVE);
    }

    private AvailabilityResult evaluate(RoomSnapshot room, List<ClaimSnapshot> claims) {
        return evaluator.evaluate(room, seats(), alwaysOpen(), claims, slot(9, 10), null);
    }

    @Test
    @DisplayName("无占用时，只有停用座位不可用")
    void noClaimsOnlyDisabledSeatUnavailable() {
        AvailabilityResult result = evaluate(openRoom(), List.of());

        assertEquals(2, result.availableSeatCount(), "3 个座位里有 1 个停用，可预约数为 2");
        assertTrue(result.roomAvailable());

        assertEquals(List.of(), result.seats().get(0).reasons(), "A 应可用");
        assertEquals(List.of(), result.seats().get(1).reasons(), "B 应可用");
        assertEquals(List.of(UnavailableReason.SEAT_DISABLED), result.seats().get(2).reasons());
    }

    @Test
    @DisplayName("整室占用让全部座位不可用，包括停用座位")
    void wholeRoomClaimBlocksEverySeat() {
        AvailabilityResult result = evaluate(openRoom(), List.of(whole(ClaimState.ACTIVE, slot(9, 11))));

        assertEquals(0, result.availableSeatCount());
        assertEquals(List.of(UnavailableReason.WHOLE_OCCUPIED), result.roomReasons());
        result.seats().forEach(seat -> assertTrue(
                seat.reasons().contains(UnavailableReason.WHOLE_OCCUPIED),
                "整间占用必须作用到每个座位"));
    }

    @Test
    @DisplayName("单座停用只影响该座，不波及同室其他座位")
    void singleSeatAdminRestrictionIsLocal() {
        AvailabilityResult result = evaluate(openRoom(), List.of(adminAt(SEAT_A, slot(9, 10))));

        assertEquals(1, result.availableSeatCount(), "A 被单座停用，只剩 B 可用");
        assertEquals(List.of(UnavailableReason.ADMIN_RESTRICTION), result.seats().get(0).reasons());
        assertEquals(List.of(), result.seats().get(1).reasons(), "B 不受影响");
        assertEquals(List.of(), result.roomReasons(), "单座停用不产生房间级原因");
    }

    @Test
    @DisplayName("个人占用只影响该座")
    void personalClaimIsLocal() {
        AvailabilityResult result = evaluate(openRoom(), List.of(personalAt(SEAT_B, slot(9, 10))));

        assertEquals(List.of(), result.seats().get(0).reasons());
        assertEquals(List.of(UnavailableReason.PERSONAL_OCCUPIED), result.seats().get(1).reasons());
        assertEquals(1, result.availableSeatCount());
    }

    @Test
    @DisplayName("多项限制同时返回，且按固定顺序排列")
    void multipleReasonsOrderedAndNotShortCircuited() {
        RoomSnapshot disabledRoom = new RoomSnapshot(ROOM_ID, "R001", false, true, null, 4);
        List<ClaimSnapshot> claims = List.of(
                whole(ClaimState.ACTIVE, slot(9, 11)),
                adminAt(SEAT_A, slot(9, 10)));

        AvailabilityResult result = evaluate(disabledRoom, claims);

        assertEquals(
                List.of(UnavailableReason.ROOM_DISABLED, UnavailableReason.ADMIN_RESTRICTION,
                        UnavailableReason.WHOLE_OCCUPIED),
                result.seats().get(0).reasons(),
                "房间停用不能掩盖其他来源；顺序必须是 房间→ADMIN→WHOLE");

        assertEquals(
                List.of(UnavailableReason.ROOM_DISABLED, UnavailableReason.WHOLE_OCCUPIED),
                result.seats().get(1).reasons());

        assertEquals(
                List.of(UnavailableReason.ROOM_DISABLED, UnavailableReason.SEAT_DISABLED,
                        UnavailableReason.WHOLE_OCCUPIED),
                result.seats().get(2).reasons(),
                "停用座位同时继承房间级与整间占用原因");
    }

    @Test
    @DisplayName("终态占用不参与判定")
    void terminalClaimDoesNotBlock() {
        AvailabilityResult result = evaluate(openRoom(), List.of(whole(ClaimState.RELEASED, slot(9, 11))));

        assertEquals(2, result.availableSeatCount(), "已释放的整室占用不得继续封锁");
        assertEquals(List.of(), result.roomReasons());
    }

    @Test
    @DisplayName("端点相接的占用不算冲突")
    void adjacentClaimDoesNotBlock() {
        // 占用 10:00-11:00，窗口 09:00-10:00
        AvailabilityResult result = evaluate(openRoom(), List.of(whole(ClaimState.ACTIVE, slot(10, 11))));

        assertEquals(2, result.availableSeatCount(), "半开区间下 10:00 结束与 10:00 开始不重叠");
    }

    @Test
    @DisplayName("院系不符时返回 DEPARTMENT_RESTRICTED")
    void departmentRestriction() {
        RoomSnapshot deptRoom = new RoomSnapshot(ROOM_ID, "R001", true, false, DEPT_ONE, 4);
        UUID otherDept = UUID.fromString("99999999-9999-4999-8999-999999999992");

        AvailabilityResult result = evaluator.evaluate(
                deptRoom, seats(), alwaysOpen(), List.of(), slot(9, 10), otherDept);

        assertEquals(List.of(UnavailableReason.DEPARTMENT_RESTRICTED), result.roomReasons());
        assertEquals(0, result.availableSeatCount());
    }

    @Test
    @DisplayName("开放时间之外返回 CLOSED_HOURS")
    void closedHours() {
        AvailabilityResult result = evaluator.evaluate(
                openRoom(), seats(), alwaysOpen(), List.of(),
                Interval.of(DAY.atTime(7, 0).atZone(OpeningCalendar.ZONE).toInstant(),
                        DAY.atTime(8, 0).atZone(OpeningCalendar.ZONE).toInstant()),
                null);

        assertEquals(List.of(UnavailableReason.CLOSED_HOURS), result.roomReasons());
        assertEquals(0, result.availableSeatCount());
    }
}
