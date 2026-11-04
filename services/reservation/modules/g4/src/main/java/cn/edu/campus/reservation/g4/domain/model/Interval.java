package cn.edu.campus.reservation.g4.domain.model;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * 半开时间区间 {@code [start, end)}。
 *
 * <p>项目基线（需求 §1.4、技术 §4.1）规定所有业务区间都是半开区间：
 * 09:00—10:00 与 10:00—11:00 不重叠。本类型是这条规则在代码里的唯一表达，
 * 任何区间比较都不得直接比较 {@link Instant}，必须经过本类。
 *
 * <p>不可变；构造时强制 {@code start < end}，零时长与倒序一律拒绝。
 */
public record Interval(Instant start, Instant end) {

    public Interval {
        Objects.requireNonNull(start, "start 不能为空");
        Objects.requireNonNull(end, "end 不能为空");
        if (!start.isBefore(end)) {
            throw new IllegalArgumentException("start 必须严格早于 end，实际为 " + start + " / " + end);
        }
    }

    public static Interval of(Instant start, Instant end) {
        return new Interval(start, end);
    }

    /** 半开语义下的重叠判定：端点相接不算重叠。 */
    public boolean overlaps(Interval other) {
        Objects.requireNonNull(other, "other 不能为空");
        return start.isBefore(other.end) && other.start.isBefore(end);
    }

    /** 半开语义下的包含判定：{@code this} 是否完整覆盖 {@code other}。 */
    public boolean contains(Interval other) {
        Objects.requireNonNull(other, "other 不能为空");
        return !start.isAfter(other.start) && !end.isBefore(other.end);
    }

    public Duration length() {
        return Duration.between(start, end);
    }

    /** 两区间交集；无交集返回 {@code null}，不用空区间表达"无交集"。 */
    public Interval intersection(Interval other) {
        Objects.requireNonNull(other, "other 不能为空");
        Instant from = start.isAfter(other.start) ? start : other.start;
        Instant to = end.isBefore(other.end) ? end : other.end;
        return from.isBefore(to) ? new Interval(from, to) : null;
    }
}
