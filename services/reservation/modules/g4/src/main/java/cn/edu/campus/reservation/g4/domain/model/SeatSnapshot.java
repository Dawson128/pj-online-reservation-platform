package cn.edu.campus.reservation.g4.domain.model;

import java.util.Objects;
import java.util.UUID;

/**
 * 判定可用性时需要的座位快照。
 *
 * @param id      座位 ID
 * @param code    座位编号，仅用于日志与错误信息
 * @param enabled 是否有效；注销后历史保留
 */
public record SeatSnapshot(UUID id, String code, boolean enabled) {

    public SeatSnapshot {
        Objects.requireNonNull(id, "id 不能为空");
        Objects.requireNonNull(code, "code 不能为空");
    }
}
