package cn.edu.campus.reservation.g4.domain.model;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 一次可用性判定的结果。
 *
 * <p>刻意不以单个 {@code available} 布尔值表达：需求 §7.3 要求同时给出
 * 全部生效的限制来源，页面据此说明"为什么不可用"。
 *
 * @param availableSeatCount 按实际限制算出的可预约座位数；<b>不能用登记座位总数代替</b>
 * @param roomReasons        房间级原因（停用、院系、开放时间、整间占用）
 * @param seats              每个座位的判定结果
 */
public record AvailabilityResult(
        int availableSeatCount,
        List<UnavailableReason> roomReasons,
        List<SeatResult> seats) {

    public AvailabilityResult {
        Objects.requireNonNull(roomReasons, "roomReasons 不能为空");
        Objects.requireNonNull(seats, "seats 不能为空");
        if (availableSeatCount < 0) {
            throw new IllegalArgumentException("availableSeatCount 不能为负");
        }
        roomReasons = List.copyOf(roomReasons);
        seats = List.copyOf(seats);
    }

    /** 房间整体是否可用（没有任何房间级原因）。 */
    public boolean roomAvailable() {
        return roomReasons.isEmpty();
    }

    /**
     * 单个座位的判定结果。
     *
     * @param seatId  座位 ID
     * @param reasons 该座位生效的全部原因，按 {@link UnavailableReason} 声明顺序排列
     */
    public record SeatResult(UUID seatId, List<UnavailableReason> reasons) {

        public SeatResult {
            Objects.requireNonNull(seatId, "seatId 不能为空");
            Objects.requireNonNull(reasons, "reasons 不能为空");
            reasons = List.copyOf(reasons);
        }

        public boolean available() {
            return reasons.isEmpty();
        }
    }
}
