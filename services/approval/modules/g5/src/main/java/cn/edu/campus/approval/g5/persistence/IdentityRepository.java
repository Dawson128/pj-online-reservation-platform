package cn.edu.campus.approval.g5.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import cn.edu.campus.approval.g5.api.Principal;
import cn.edu.campus.approval.g5.domain.Credentials;

@org.springframework.context.annotation.Profile("!shell")
@Repository
public class IdentityRepository {
    private final JdbcTemplate jdbc;
    public IdentityRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record Account(UUID id, String displayName, UUID departmentId, String passwordHash,
                          boolean enabled, boolean mustChangePassword, long version, long permissionVersion) {
        @Override public String toString() { return "Account[id=" + id + ", credentials=REDACTED]"; }
    }
    public record ActiveSession(UUID id, Account account, String csrfHash, OffsetDateTime expiresAt) { }

    private Account account(ResultSet row, int index) throws SQLException {
        return new Account(row.getObject("id", UUID.class), row.getString("display_name"),
                row.getObject("department_id", UUID.class), row.getString("password_hash"),
                row.getBoolean("enabled"), row.getBoolean("must_change_password"),
                row.getLong("version"), row.getLong("permission_version"));
    }
    public Account lockAccountByCode(String code) {
        List<Account> items = jdbc.query("SELECT * FROM m2.account WHERE user_code=? FOR UPDATE", this::account, code);
        return items.isEmpty() ? null : items.getFirst();
    }
    public Account lockAccount(UUID id) {
        List<Account> items = jdbc.query("SELECT * FROM m2.account WHERE id=? FOR UPDATE", this::account, id);
        return items.isEmpty() ? null : items.getFirst();
    }
    public ActiveSession activeSession(String token) {
        if (token == null || token.length() < 32 || token.length() > 256) { return null; }
        List<ActiveSession> items = jdbc.query("""
                SELECT a.*, s.id AS session_id, s.csrf_hash, s.expires_at
                FROM m2.session s JOIN m2.account a ON a.id=s.account_id
                WHERE s.token_hash=? AND s.revoked_at IS NULL AND s.expires_at>CURRENT_TIMESTAMP
                  AND a.enabled AND s.account_version=a.version AND s.permission_version=a.permission_version
                """, (row, i) -> new ActiveSession(row.getObject("session_id", UUID.class),
                account(row, i), row.getString("csrf_hash"), row.getObject("expires_at", OffsetDateTime.class)),
                Credentials.hash(token));
        return items.isEmpty() ? null : items.getFirst();
    }
    public ActiveSession createSession(Account account, String token, String csrf) {
        jdbc.update("""
                INSERT INTO m2.session(id,account_id,token_hash,csrf_hash,account_version,permission_version,expires_at)
                VALUES (?,?,?,?,?,?,CURRENT_TIMESTAMP + interval '8 hours')
                """, UUID.randomUUID(), account.id(), Credentials.hash(token), Credentials.hash(csrf),
                account.version(), account.permissionVersion());
        return activeSession(token);
    }
    public Principal principal(ActiveSession session) {
        Account account = session.account();
        return new Principal(account.id(), account.displayName(), account.departmentId(),
                Long.toString(account.version()), Long.toString(account.permissionVersion()), grants(account.id()),
                false, time(session.expiresAt()));
    }
    public List<Principal.Grant> grants(UUID accountId) {
        return jdbc.query("""
                SELECT DISTINCT g.action,g.scope_kind,g.department_id,g.room_id FROM m2.role_binding b
                JOIN m2.role_grant g ON g.role_id=b.role_id WHERE b.account_id=?
                ORDER BY g.action,g.scope_kind,g.department_id,g.room_id
                """, (row, i) -> {
                    String kind = row.getString("scope_kind");
                    Map<String, Object> scope = switch (kind) {
                        case "DEPARTMENT" -> Map.of("kind", kind, "departmentId", row.getObject("department_id", UUID.class));
                        case "ROOM" -> Map.of("kind", kind, "roomId", row.getObject("room_id", UUID.class));
                        default -> Map.of("kind", kind);
                    };
                    return new Principal.Grant(row.getString("action"), scope);
                }, accountId);
    }
    public void revokeSession(UUID id) {
        jdbc.update("UPDATE m2.session SET revoked_at=CURRENT_TIMESTAMP WHERE id=? AND revoked_at IS NULL", id);
    }
    public void changePassword(Account account, String hash) {
        jdbc.update("""
                UPDATE m2.account SET password_hash=?,must_change_password=false,version=version+1,
                updated_at=CURRENT_TIMESTAMP WHERE id=?
                """, hash, account.id());
        jdbc.update("UPDATE m2.session SET revoked_at=CURRENT_TIMESTAMP WHERE account_id=? AND revoked_at IS NULL", account.id());
    }
    public OffsetDateTime now() { return jdbc.queryForObject("SELECT CURRENT_TIMESTAMP", OffsetDateTime.class); }
    public static String time(OffsetDateTime value) {
        return value.withOffsetSameInstant(ZoneOffset.ofHours(8))
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX"));
    }
}
