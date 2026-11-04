package cn.edu.campus.approval.g5.persistence;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import cn.edu.campus.approval.g5.domain.Credentials;

/** Rows are locked in account-then-IP order inside the caller's transaction. */
@org.springframework.context.annotation.Profile("!shell")
@Repository
public class AuthRateLimiter {
    private final JdbcTemplate jdbc;
    public AuthRateLimiter(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public record Bucket(String name, String subject) { }
    public record Check(List<Bucket> buckets, int retryAfter) { }

    public Check lock(String operation, String account, String ip, OffsetDateTime now) {
        List<Bucket> buckets = new ArrayList<>();
        buckets.add(new Bucket(operation + "_ACCOUNT", Credentials.hash(account)));
        buckets.add(new Bucket(operation + "_IP", Credentials.hash(ip == null ? "unknown" : ip)));
        int retry = 0;
        for (Bucket bucket : buckets) {
            jdbc.update("INSERT INTO m2.g5_auth_throttle(bucket,subject_hash) VALUES (?,?) ON CONFLICT DO NOTHING",
                    bucket.name(), bucket.subject());
            jdbc.queryForMap("SELECT * FROM m2.g5_auth_throttle WHERE bucket=? AND subject_hash=? FOR UPDATE",
                    bucket.name(), bucket.subject());
            OffsetDateTime blocked = jdbc.queryForObject("""
                    SELECT blocked_until FROM m2.g5_auth_throttle WHERE bucket=? AND subject_hash=?
                    """, OffsetDateTime.class, bucket.name(), bucket.subject());
            OffsetDateTime window = jdbc.queryForObject("""
                    SELECT window_started_at FROM m2.g5_auth_throttle WHERE bucket=? AND subject_hash=?
                    """, OffsetDateTime.class, bucket.name(), bucket.subject());
            if (blocked != null && blocked.isAfter(now)) {
                retry = Math.max(retry, (int) Math.ceil(Duration.between(now, blocked).toMillis() / 1000.0));
            } else if (blocked != null || !window.isAfter(now.minusMinutes(5))) {
                jdbc.update("""
                        UPDATE m2.g5_auth_throttle SET failures=0,blocked_until=NULL,window_started_at=CURRENT_TIMESTAMP
                        WHERE bucket=? AND subject_hash=?
                        """, bucket.name(), bucket.subject());
            }
        }
        return new Check(buckets, retry);
    }
    public void failed(Check check) {
        for (Bucket bucket : check.buckets()) {
            jdbc.update("""
                    UPDATE m2.g5_auth_throttle SET failures=failures+1,
                    blocked_until=CASE WHEN failures+1>=10 THEN CURRENT_TIMESTAMP+interval '5 minutes' ELSE NULL END
                    WHERE bucket=? AND subject_hash=?
                    """, bucket.name(), bucket.subject());
        }
    }
}
