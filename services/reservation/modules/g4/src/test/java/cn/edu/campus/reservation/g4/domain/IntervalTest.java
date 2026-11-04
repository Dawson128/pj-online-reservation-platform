package cn.edu.campus.reservation.g4.domain;

import cn.edu.campus.reservation.g4.domain.model.Interval;
import cn.edu.campus.reservation.g4.domain.model.OpeningCalendar;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link Interval} 的半开语义。这些边界一旦写错，整个可用性判定都会跟着错。 */
class IntervalTest {

    private static final LocalDate DAY = LocalDate.of(2026, 11, 4);

    private static Instant at(int hour, int minute) {
        return DAY.atTime(hour, minute).atZone(OpeningCalendar.ZONE).toInstant();
    }

    @Test
    @DisplayName("相邻区间不重叠：09:00-10:00 与 10:00-11:00")
    void adjacentIntervalsDoNotOverlap() {
        Interval morning = Interval.of(at(9, 0), at(10, 0));
        Interval next = Interval.of(at(10, 0), at(11, 0));

        assertFalse(morning.overlaps(next), "半开区间端点相接不算重叠");
        assertFalse(next.overlaps(morning), "重叠判定必须对称");
    }

    @Test
    @DisplayName("重叠一分钟也算重叠")
    void oneMinuteOverlapCounts() {
        Interval a = Interval.of(at(9, 0), at(10, 0));
        Interval b = Interval.of(at(9, 59), at(11, 0));

        assertTrue(a.overlaps(b));
        assertTrue(b.overlaps(a));
    }

    @Test
    @DisplayName("零时长与倒序区间一律拒绝")
    void zeroAndReversedRejected() {
        assertThrows(IllegalArgumentException.class, () -> Interval.of(at(9, 0), at(9, 0)),
                "零时长区间没有意义，必须拒绝");
        assertThrows(IllegalArgumentException.class, () -> Interval.of(at(10, 0), at(9, 0)),
                "倒序区间必须拒绝");
    }

    @Test
    @DisplayName("包含判定：端点相等视为完整包含")
    void containsInclusiveBoundaries() {
        Interval outer = Interval.of(at(8, 0), at(22, 0));

        assertTrue(outer.contains(Interval.of(at(8, 0), at(22, 0))));
        assertTrue(outer.contains(Interval.of(at(9, 0), at(10, 0))));
        assertFalse(outer.contains(Interval.of(at(7, 59), at(10, 0))));
        assertFalse(outer.contains(Interval.of(at(9, 0), at(22, 1))));
    }

    @Test
    @DisplayName("交集：无交集返回 null，有交集返回正确边界")
    void intersection() {
        Interval a = Interval.of(at(9, 0), at(11, 0));
        Interval b = Interval.of(at(10, 0), at(12, 0));

        assertEquals(Interval.of(at(10, 0), at(11, 0)), a.intersection(b));

        assertNull(a.intersection(Interval.of(at(11, 0), at(12, 0))),
                "端点相接时没有实际交集，必须返回 null 而不是空区间");
    }
}
