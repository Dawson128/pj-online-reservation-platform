package cn.edu.campus.reservation.g4.domain.model;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 房间开放时间表，负责回答唯一一个问题：给定的半开区间是否<b>完整</b>落在开放时间内。
 *
 * <p>业务规则（需求 §6.2、技术 §4.3）：
 * <ul>
 *   <li>时间一律按 {@code Asia/Shanghai} 解释；该时区无夏令时，分钟运算安全。</li>
 *   <li>周规则：weekday 1=周一 … 7=周日，每日期最多 8 段，段内 {@code start < end}，段间不得重叠。</li>
 *   <li>指定日期例外<b>整体覆盖</b>当日周规则（不是叠加）；有例外但没有任何开放段表示整日关闭。</li>
 *   <li>分钟以 0..1440 表示 HH:mm，1440 即 24:00，只允许出现在结束位。</li>
 *   <li>判定为"完整覆盖"：区间跨越的每一天，其切片都必须被当日开放段完全覆盖。
 *       只要有一天有缺口，整条区间就不算开放——不做部分放行。</li>
 * </ul>
 *
 * <p>不可变。构造时校验重叠与取值，非法输入直接抛 {@link IllegalArgumentException}，
 * 由上层映射为 400。
 */
public final class OpeningCalendar {

    /** 业务时区。全项目统一，不从系统默认时区推断。 */
    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    /** 24:00 对应的分钟值，仅可作区间结束位。 */
    public static final int FULL_DAY_END_MINUTE = 1440;

    /** 每日期最多 8 段（技术 §4.3）。 */
    public static final int MAX_RANGES_PER_DAY = 8;

    /** 周规则中的一段开放区间。 */
    public record WeeklyRule(int weekday, int startMinute, int endMinute) {
        public WeeklyRule {
            if (weekday < 1 || weekday > 7) {
                throw new IllegalArgumentException("weekday 必须在 1..7，实际 " + weekday);
            }
            MinuteRange.validate(startMinute, endMinute);
        }
    }

    /** 分钟区间 {@code [start, end)}，取值 0..1440。 */
    public record MinuteRange(int start, int end) {
        public MinuteRange {
            validate(start, end);
        }

        static void validate(int start, int end) {
            if (start < 0 || start > FULL_DAY_END_MINUTE - 1) {
                throw new IllegalArgumentException("startMinute 必须在 0..1439，实际 " + start);
            }
            if (end < 1 || end > FULL_DAY_END_MINUTE) {
                throw new IllegalArgumentException("endMinute 必须在 1..1440，实际 " + end);
            }
            if (start >= end) {
                throw new IllegalArgumentException("startMinute 必须小于 endMinute，实际 " + start + " / " + end);
            }
        }

        public int lengthMinutes() {
            return end - start;
        }
    }

    private final List<WeeklyRule> weekly;
    private final Map<LocalDate, List<MinuteRange>> exceptions;

    /**
     * @param weekly     周规则，可为空表示从不开放
     * @param exceptions 指定日期例外；键存在但值为空列表表示该日整日关闭
     */
    public OpeningCalendar(List<WeeklyRule> weekly, Map<LocalDate, List<MinuteRange>> exceptions) {
        Objects.requireNonNull(weekly, "weekly 不能为空");
        Objects.requireNonNull(exceptions, "exceptions 不能为空");
        this.weekly = List.copyOf(weekly);
        this.exceptions = deepCopy(exceptions);
        validateNoOverlapPerDay(this.weekly, this.exceptions);
    }

    /** 未配置任何规则的时间表：任何区间都不开放。这与"房间新建后默认不开放"一致。 */
    public static OpeningCalendar neverOpen() {
        return new OpeningCalendar(List.of(), Map.of());
    }

    private static Map<LocalDate, List<MinuteRange>> deepCopy(Map<LocalDate, List<MinuteRange>> source) {
        Map<LocalDate, List<MinuteRange>> copy = new HashMap<>();
        source.forEach((date, ranges) -> {
            Objects.requireNonNull(date, "例外日期不能为空");
            Objects.requireNonNull(ranges, "例外区间列表不能为空（整日关闭请给空列表）");
            if (ranges.size() > MAX_RANGES_PER_DAY) {
                throw new IllegalArgumentException(
                        date + " 的开放段超过 " + MAX_RANGES_PER_DAY + " 段，实际 " + ranges.size());
            }
            copy.put(date, List.copyOf(ranges));
        });
        return Map.copyOf(copy);
    }

    /** 同一天内的开放段不得重叠（需求 §6.2「重叠/日期重复 400」）。 */
    private static void validateNoOverlapPerDay(List<WeeklyRule> weekly,
                                                Map<LocalDate, List<MinuteRange>> exceptions) {
        for (int weekday = 1; weekday <= 7; weekday++) {
            // 循环变量在 lambda 中不是 effectively final，先固定成局部常量
            final int currentWeekday = weekday;
            List<MinuteRange> ranges = weekly.stream()
                    .filter(rule -> rule.weekday() == currentWeekday)
                    .map(rule -> new MinuteRange(rule.startMinute(), rule.endMinute()))
                    .sorted(Comparator.comparingInt(MinuteRange::start))
                    .toList();
            if (ranges.size() > MAX_RANGES_PER_DAY) {
                throw new IllegalArgumentException(
                        "weekday " + weekday + " 的开放段超过 " + MAX_RANGES_PER_DAY + " 段");
            }
            assertNoOverlap(ranges, "weekday " + weekday);
        }
        exceptions.forEach((date, ranges) -> {
            List<MinuteRange> sorted = ranges.stream()
                    .sorted(Comparator.comparingInt(MinuteRange::start))
                    .toList();
            assertNoOverlap(sorted, date.toString());
        });
    }

    private static void assertNoOverlap(List<MinuteRange> sortedRanges, String scope) {
        for (int i = 1; i < sortedRanges.size(); i++) {
            MinuteRange prev = sortedRanges.get(i - 1);
            MinuteRange curr = sortedRanges.get(i);
            if (curr.start() < prev.end()) {
                throw new IllegalArgumentException(
                        scope + " 存在重叠开放段：[" + prev.start() + "," + prev.end() + ") 与 ["
                                + curr.start() + "," + curr.end() + ")");
            }
        }
    }

    /** 某日的生效开放段：有例外则整体覆盖，否则用周规则；空列表表示当日关闭。 */
    public List<MinuteRange> rangesFor(LocalDate date) {
        Objects.requireNonNull(date, "date 不能为空");
        if (exceptions.containsKey(date)) {
            return exceptions.get(date);
        }
        int weekday = date.getDayOfWeek().getValue();
        List<MinuteRange> ranges = new ArrayList<>();
        for (WeeklyRule rule : weekly) {
            if (rule.weekday() == weekday) {
                ranges.add(new MinuteRange(rule.startMinute(), rule.endMinute()));
            }
        }
        ranges.sort(Comparator.comparingInt(MinuteRange::start));
        return List.copyOf(ranges);
    }

    /** 该日是否整日关闭。 */
    public boolean closedAllDay(LocalDate date) {
        return rangesFor(date).isEmpty();
    }

    /**
     * 区间是否被开放时间<b>完整</b>覆盖。
     *
     * <p>按业务日切片逐日判定：跨日区间每一天的切片都必须被当日开放段完全覆盖。
     */
    public boolean fullyOpen(Interval window) {
        Objects.requireNonNull(window, "window 不能为空");

        LocalDate firstDate = window.start().atZone(ZONE).toLocalDate();
        LocalDate lastDate = window.end().atZone(ZONE).toLocalDate();

        for (LocalDate date = firstDate; !date.isAfter(lastDate); date = date.plusDays(1)) {
            Instant dayStart = date.atStartOfDay(ZONE).toInstant();
            Instant dayEnd = date.plusDays(1).atStartOfDay(ZONE).toInstant();
            Interval daySlice = window.intersection(new Interval(dayStart, dayEnd));
            if (daySlice == null) {
                // 结束时刻恰好在当日 00:00 时，最后一天没有实际切片
                continue;
            }
            int from = minuteOfDay(daySlice.start(), date);
            int to = minuteOfDay(daySlice.end(), date);
            if (!coveredBy(rangesFor(date), from, to)) {
                return false;
            }
        }
        return true;
    }

    /** 求某时刻在当日的分钟偏移；当日 24:00 返回 1440。 */
    private static int minuteOfDay(Instant instant, LocalDate date) {
        Instant dayStart = date.atStartOfDay(ZONE).toInstant();
        long seconds = instant.getEpochSecond() - dayStart.getEpochSecond();
        if (seconds == 24L * 3600) {
            return FULL_DAY_END_MINUTE;
        }
        if (seconds < 0 || seconds > 24L * 3600) {
            throw new IllegalArgumentException("时刻 " + instant + " 不属于日期 " + date);
        }
        return (int) (seconds / 60);
    }

    /** {@code [from, to)} 是否被有序不重叠的开放段完全覆盖。 */
    private static boolean coveredBy(List<MinuteRange> ranges, int from, int to) {
        int cursor = from;
        for (MinuteRange range : ranges) {
            if (range.end() <= cursor) {
                continue;
            }
            if (range.start() > cursor) {
                // 出现缺口
                return false;
            }
            cursor = range.end();
            if (cursor >= to) {
                return true;
            }
        }
        return cursor >= to;
    }
}
