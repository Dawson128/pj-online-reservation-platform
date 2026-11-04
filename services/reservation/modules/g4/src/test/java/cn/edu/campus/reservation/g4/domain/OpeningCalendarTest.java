package cn.edu.campus.reservation.g4.domain;

import cn.edu.campus.reservation.g4.domain.model.Interval;
import cn.edu.campus.reservation.g4.domain.model.OpeningCalendar;
import cn.edu.campus.reservation.g4.domain.model.OpeningCalendar.MinuteRange;
import cn.edu.campus.reservation.g4.domain.model.OpeningCalendar.WeeklyRule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 开放时间判定：完整覆盖、当日例外整体覆盖周规则、跨午夜、重叠拒绝。 */
class OpeningCalendarTest {

    private static final LocalDate DAY = LocalDate.of(2026, 11, 4);
    private static final int WEEKDAY = DAY.getDayOfWeek().getValue();

    private static Instant at(LocalDate date, int hour, int minute) {
        return date.atTime(hour, minute).atZone(OpeningCalendar.ZONE).toInstant();
    }

    private static Interval window(LocalDate date, int fromHour, int toHour) {
        return Interval.of(at(date, fromHour, 0), at(date, toHour, 0));
    }

    /** 该日 08:00-22:00 开放。 */
    private static OpeningCalendar standardDay() {
        return new OpeningCalendar(
                List.of(new WeeklyRule(WEEKDAY, 8 * 60, 22 * 60)),
                Map.of());
    }

    @Test
    @DisplayName("完整落在开放时间内才算开放")
    void fullyInsideIsOpen() {
        OpeningCalendar calendar = standardDay();

        assertTrue(calendar.fullyOpen(window(DAY, 9, 10)));
        assertTrue(calendar.fullyOpen(Interval.of(at(DAY, 8, 0), at(DAY, 22, 0))),
                "端点正好等于开放边界应视为完整覆盖");
    }

    @Test
    @DisplayName("任一端越界都不算开放，不做部分放行")
    void partialOverlapIsNotOpen() {
        OpeningCalendar calendar = standardDay();

        assertFalse(calendar.fullyOpen(window(DAY, 7, 9)), "开始早于开放时间");
        assertFalse(calendar.fullyOpen(window(DAY, 21, 23)), "结束晚于开放时间");
        assertFalse(calendar.fullyOpen(Interval.of(at(DAY, 7, 0), at(DAY, 23, 0))), "两端都越界");
    }

    @Test
    @DisplayName("未配置规则表示从不开放（新建房间默认不开放）")
    void neverOpenCalendar() {
        assertFalse(OpeningCalendar.neverOpen().fullyOpen(window(DAY, 9, 10)));
    }

    @Test
    @DisplayName("当日例外整体覆盖周规则，不是叠加")
    void exceptionOverridesWeekly() {
        // 周规则 08:00-22:00，但该日例外只有 10:00-18:00
        OpeningCalendar calendar = new OpeningCalendar(
                List.of(new WeeklyRule(WEEKDAY, 8 * 60, 22 * 60)),
                Map.of(DAY, List.of(new MinuteRange(10 * 60, 18 * 60))));

        assertFalse(calendar.fullyOpen(window(DAY, 9, 10)),
                "09:00 在周规则内，但该日例外从 10:00 开始，必须拒绝");
        assertTrue(calendar.fullyOpen(window(DAY, 10, 18)));
        assertFalse(calendar.fullyOpen(window(DAY, 18, 19)), "当日例外 18:00 结束");
    }

    @Test
    @DisplayName("例外给空列表表示整日关闭")
    void emptyExceptionMeansClosedAllDay() {
        OpeningCalendar calendar = new OpeningCalendar(
                List.of(new WeeklyRule(WEEKDAY, 8 * 60, 22 * 60)),
                Map.of(DAY, List.of()));

        assertTrue(calendar.closedAllDay(DAY));
        assertFalse(calendar.fullyOpen(window(DAY, 9, 10)));
    }

    @Test
    @DisplayName("跨午夜区间按两日切片分别判定")
    void crossMidnightSplitsByDay() {
        LocalDate nextDay = DAY.plusDays(1);
        OpeningCalendar calendar = new OpeningCalendar(
                List.of(
                        new WeeklyRule(WEEKDAY, 23 * 60, 24 * 60),
                        new WeeklyRule(nextDay.getDayOfWeek().getValue(), 0, 2 * 60)),
                Map.of());

        Interval crossing = Interval.of(at(DAY, 23, 0), at(nextDay, 1, 0));
        assertTrue(calendar.fullyOpen(crossing), "两日的开放段拼起来应覆盖整段");

        Interval beyondSecondDay = Interval.of(at(DAY, 23, 0), at(nextDay, 3, 0));
        assertFalse(calendar.fullyOpen(beyondSecondDay), "次日开放只到 02:00，03:00 越界");
    }

    @Test
    @DisplayName("同日开放段重叠必须拒绝构造")
    void overlappingWeeklyRulesRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new OpeningCalendar(
                        List.of(
                                new WeeklyRule(WEEKDAY, 8 * 60, 12 * 60),
                                new WeeklyRule(WEEKDAY, 11 * 60, 14 * 60)),
                        Map.of()),
                "重叠的开放段会让覆盖判定产生歧义，应在构造期就拒绝");
    }

    @Test
    @DisplayName("每日期开放段超过 8 段必须拒绝")
    void moreThanEightRangesRejected() {
        List<WeeklyRule> tooMany = List.of(
                new WeeklyRule(WEEKDAY, 0, 60),
                new WeeklyRule(WEEKDAY, 61, 120),
                new WeeklyRule(WEEKDAY, 121, 180),
                new WeeklyRule(WEEKDAY, 181, 240),
                new WeeklyRule(WEEKDAY, 241, 300),
                new WeeklyRule(WEEKDAY, 301, 360),
                new WeeklyRule(WEEKDAY, 361, 420),
                new WeeklyRule(WEEKDAY, 421, 480),
                new WeeklyRule(WEEKDAY, 481, 540));

        assertThrows(IllegalArgumentException.class,
                () -> new OpeningCalendar(tooMany, Map.of()));
    }
}
