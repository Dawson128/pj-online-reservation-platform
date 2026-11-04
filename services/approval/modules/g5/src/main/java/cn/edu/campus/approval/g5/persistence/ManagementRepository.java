package cn.edu.campus.approval.g5.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import cn.edu.campus.approval.g5.api.IdentityException;

/** Owns only G5 tables; all mutations participate in the caller's transaction. */
@org.springframework.context.annotation.Profile("!shell")
@Repository
public class ManagementRepository {
    private final JdbcTemplate jdbc;
    private static final String ACCOUNT = "SELECT a.id,a.user_code,a.display_name,a.department_id,a.email,a.enabled,a.version,a.permission_version,a.created_at,a.updated_at FROM m2.account a";
    public ManagementRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public record Cached(String digest, String response, int status, boolean live) { }
    public record Intent(String digest, String kind, UUID id) { }

    public void lockManagement() { jdbc.queryForObject("SELECT id FROM m2.g5_admin_guard WHERE id=1 FOR UPDATE", Integer.class); }
    public Map<String, Object> account(UUID id, boolean lock) {
        return one(ACCOUNT + " WHERE a.id=?" + (lock ? " FOR UPDATE" : ""), this::accountRow, id);
    }
    public Map<String, Object> department(UUID id, boolean lock) {
        return one("SELECT * FROM m2.department WHERE id=?" + (lock ? " FOR UPDATE" : ""), this::departmentRow, id);
    }
    public Map<String, Object> role(UUID id) { return one("SELECT * FROM m2.role WHERE id=?", this::roleRow, id); }
    public List<UUID> roleIds(UUID account) {
        return jdbc.query("SELECT role_id FROM m2.role_binding WHERE account_id=? ORDER BY role_id", (row, i) -> row.getObject(1, UUID.class), account);
    }
    private Map<String, Object> accountRow(ResultSet row, int index) throws SQLException {
        Map<String, Object> result = meta(row);
        result.put("userCode", row.getString("user_code")); result.put("displayName", row.getString("display_name"));
        result.put("departmentId", row.getObject("department_id", UUID.class)); result.put("email", row.getString("email"));
        result.put("enabled", row.getBoolean("enabled")); result.put("mustChangePassword", false);
        result.put("roleIds", roleIds(row.getObject("id", UUID.class))); result.put("permissionVersion", Long.toString(row.getLong("permission_version")));
        return result;
    }
    private Map<String, Object> departmentRow(ResultSet row, int index) throws SQLException {
        Map<String, Object> result = meta(row);
        result.put("code", row.getString("code")); result.put("name", row.getString("name")); result.put("enabled", row.getBoolean("enabled"));
        return result;
    }
    private Map<String, Object> roleRow(ResultSet row, int index) throws SQLException {
        Map<String, Object> result = meta(row);
        result.put("key", row.getString("key")); result.put("name", row.getString("name")); result.put("builtIn", row.getBoolean("built_in"));
        result.put("grants", jdbc.query("SELECT action,scope_kind,department_id,room_id FROM m2.role_grant WHERE role_id=? ORDER BY action,scope_kind,department_id,room_id", (grant, i) -> {
            String kind = grant.getString("scope_kind");
            Map<String, Object> scope = new LinkedHashMap<>(); scope.put("kind", kind);
            if (kind.equals("ROOM")) scope.put("roomId", grant.getObject("room_id", UUID.class));
            if (kind.equals("DEPARTMENT")) scope.put("departmentId", grant.getObject("department_id", UUID.class));
            return Map.of("action", grant.getString("action"), "scope", scope);
        }, row.getObject("id", UUID.class)));
        return result;
    }
    private Map<String, Object> meta(ResultSet row) throws SQLException {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", row.getObject("id", UUID.class)); result.put("version", Long.toString(row.getLong("version")));
        result.put("createdAt", IdentityRepository.time(row.getObject("created_at", OffsetDateTime.class)));
        result.put("updatedAt", IdentityRepository.time(row.getObject("updated_at", OffsetDateTime.class)));
        return result;
    }
    private Map<String, Object> one(String sql, RowMapper<Map<String, Object>> mapper, UUID id) {
        List<Map<String, Object>> rows = jdbc.query(sql, mapper, id);
        if (rows.isEmpty()) throw new IdentityException(404, "NOT_FOUND", "记录不存在");
        return rows.getFirst();
    }
    public Map<String, Object> list(String kind, Map<String, Object> filters, int page, int size) {
        String from = kind.equals("ACCOUNT") ? "m2.account a" : kind.equals("ROLE") ? "m2.role a" : "m2.department a";
        String select = kind.equals("ACCOUNT") ? ACCOUNT : "SELECT a.* FROM " + from;
        StringBuilder where = new StringBuilder(" WHERE 1=1"); List<Object> args = new ArrayList<>();
        if (filters.containsKey("keyword")) {
            String columns = kind.equals("ACCOUNT") ? "a.user_code||' '||a.display_name" : kind.equals("ROLE") ? "a.key||' '||a.name" : "a.code||' '||a.name";
            where.append(" AND strpos(lower(").append(columns).append("),lower(?))>0"); args.add(filters.get("keyword"));
        }
        for (String key : List.of("enabled", "builtIn", "departmentId")) {
            if (filters.containsKey(key)) { where.append(" AND a.").append(key.equals("builtIn") ? "built_in" : key.equals("departmentId") ? "department_id" : key).append("=?"); args.add(filters.get(key)); }
        }
        if (filters.containsKey("roleId")) { where.append(" AND EXISTS(SELECT 1 FROM m2.role_binding b WHERE b.account_id=a.id AND b.role_id=?)"); args.add(filters.get("roleId")); }
        long total = jdbc.queryForObject("SELECT count(*) FROM " + from + where, Long.class, args.toArray());
        args.add(size); args.add((page - 1) * size);
        RowMapper<Map<String, Object>> mapper = kind.equals("ACCOUNT") ? this::accountRow : kind.equals("ROLE") ? this::roleRow : this::departmentRow;
        List<Map<String, Object>> items = jdbc.query(select + where + " ORDER BY a.created_at DESC,a.id ASC LIMIT ? OFFSET ?", mapper, args.toArray());
        return Map.of("items", items, "page", page, "pageSize", size, "total", total, "asOf", IdentityRepository.time(jdbc.queryForObject("SELECT CURRENT_TIMESTAMP", OffsetDateTime.class)));
    }
    public UUID createAccount(Map<String, Object> body, String hash) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO m2.account(id,user_code,display_name,department_id,email,password_hash) VALUES (?,?,?,?,?,?)", id, body.get("userCode"), body.get("displayName"), body.get("departmentId"), body.get("email"), hash);
        return id;
    }
    public void updateAccount(UUID id, Map<String, Object> body) {
        jdbc.update("UPDATE m2.account SET display_name=?,department_id=?,email=?,enabled=?,version=version+1,permission_version=permission_version+1,updated_at=CURRENT_TIMESTAMP WHERE id=?", body.get("displayName"), body.get("departmentId"), body.get("email"), body.get("enabled"), id);
        revoke(id);
    }
    public void bindRoles(UUID id, List<UUID> roles, boolean changeVersion) {
        jdbc.update("DELETE FROM m2.role_binding WHERE account_id=?", id);
        for (UUID role : roles) jdbc.update("INSERT INTO m2.role_binding(id,account_id,role_id) VALUES (?,?,?)", UUID.randomUUID(), id, role);
        if (changeVersion) { jdbc.update("UPDATE m2.account SET version=version+1,permission_version=permission_version+1,updated_at=CURRENT_TIMESTAMP WHERE id=?", id); revoke(id); }
    }
    private void revoke(UUID account) { jdbc.update("UPDATE m2.session SET revoked_at=CURRENT_TIMESTAMP WHERE account_id=? AND revoked_at IS NULL", account); }
    public void protectLastManager() {
        long count = jdbc.queryForObject("SELECT count(DISTINCT a.id) FROM m2.account a JOIN m2.role_binding b ON b.account_id=a.id JOIN m2.role_grant g ON g.role_id=b.role_id WHERE a.enabled AND g.action='system:manage' AND g.scope_kind='SYSTEM'", Long.class);
        if (count == 0) throw new IdentityException(409, "LAST_ADMIN_PROTECTED", "必须保留至少一个有效系统管理员");
    }
    public UUID createRole(Map<String,Object> body) {
        UUID id=UUID.randomUUID();
        jdbc.update("INSERT INTO m2.role(id,key,name,built_in) VALUES (?,?,?,false)",id,body.get("key"),body.get("name"));
        replaceGrants(id,body.get("grants")); return id;
    }
    public void updateRole(UUID id,Map<String,Object> body) {
        jdbc.update("UPDATE m2.role SET name=?,version=version+1,updated_at=CURRENT_TIMESTAMP WHERE id=?",body.get("name"),id);
        replaceGrants(id,body.get("grants"));
        // Lock accounts in stable order, matching credential updates' account -> session order.
        List<UUID> accounts=jdbc.query("SELECT a.id FROM m2.account a JOIN m2.role_binding b ON b.account_id=a.id WHERE b.role_id=? ORDER BY a.id FOR UPDATE OF a",(row,i)->row.getObject(1,UUID.class),id);
        for(UUID account:accounts) {
            jdbc.update("UPDATE m2.account SET version=version+1,permission_version=permission_version+1,updated_at=CURRENT_TIMESTAMP WHERE id=?",account);
            revoke(account);
        }
    }
    @SuppressWarnings("unchecked") private void replaceGrants(UUID id,Object value) {
        jdbc.update("DELETE FROM m2.role_grant WHERE role_id=?",id);
        for(Map<String,Object> grant:(List<Map<String,Object>>)value) {
            Map<String,Object> scope=(Map<String,Object>)grant.get("scope");
            jdbc.update("INSERT INTO m2.role_grant(id,role_id,action,scope_kind,department_id,room_id) VALUES (?,?,?,?,?,?)",UUID.randomUUID(),id,grant.get("action"),scope.get("kind"),scope.get("departmentId"),scope.get("roomId"));
        }
    }
    public UUID createDepartment(Map<String, Object> body) {
        UUID id = UUID.randomUUID(); jdbc.update("INSERT INTO m2.department(id,code,name) VALUES (?,?,?)", id, body.get("code"), body.get("name")); return id;
    }
    public void updateDepartment(UUID id, Map<String, Object> body) {
        jdbc.update("UPDATE m2.department SET name=?,enabled=?,version=version+1,updated_at=CURRENT_TIMESTAMP WHERE id=?", body.get("name"), body.get("enabled"), id);
    }
    public Map<String,Object> configuration(boolean lock) {
        var rows=jdbc.query("SELECT * FROM m2.business_config WHERE id=1"+(lock?" FOR UPDATE":""),(row,index)->{
            Map<String,Object> values=new java.util.TreeMap<>();
            values.put("reservationMaxHours",row.getInt("reservation_max_hours"));values.put("reservationAdvanceDays",row.getInt("reservation_advance_days"));
            values.put("applicationAdvanceDays",row.getInt("application_advance_days"));values.put("scanIntervalSeconds",row.getInt("scan_interval_seconds"));values.put("notificationRetryLimit",row.getInt("notification_retry_limit"));
            return Map.<String,Object>of("version",Long.toString(row.getLong("version")),"effectiveAt",IdentityRepository.time(row.getObject("effective_at",OffsetDateTime.class)),"values",values);
        });
        if(rows.isEmpty()) throw new IdentityException(503,"AUTH_UNAVAILABLE","配置尚未初始化，请联系管理员");
        return rows.getFirst();
    }
    public void updateConfiguration(Map<String,Integer> values,UUID actor) {
        jdbc.update("UPDATE m2.business_config SET version=version+1,effective_at=clock_timestamp(),reservation_max_hours=?,reservation_advance_days=?,application_advance_days=?,scan_interval_seconds=?,notification_retry_limit=? WHERE id=1",
                values.get("reservationMaxHours"),values.get("reservationAdvanceDays"),values.get("applicationAdvanceDays"),values.get("scanIntervalSeconds"),values.get("notificationRetryLimit"));
        jdbc.update("INSERT INTO m2.g5_config_history(version,effective_at,actor_id,values) SELECT version,effective_at,?,jsonb_build_object('reservationMaxHours',reservation_max_hours,'reservationAdvanceDays',reservation_advance_days,'applicationAdvanceDays',application_advance_days,'scanIntervalSeconds',scan_interval_seconds,'notificationRetryLimit',notification_retry_limit) FROM m2.business_config WHERE id=1",actor);
    }
    public Cached cached(UUID actor, String method, String path, UUID key) {
        List<Cached> rows = jdbc.query("SELECT request_hmac,response::text,status,expires_at>CURRENT_TIMESTAMP AS live FROM m2.g5_idempotency WHERE actor_id=? AND method=? AND path=? AND request_key=?", (row, i) -> new Cached(row.getString(1), row.getString(2), row.getInt(3), row.getBoolean(4)), actor, method, path, key);
        return rows.isEmpty() ? null : rows.getFirst();
    }
    public Intent intent(UUID actor, String method, String path, UUID key) {
        List<Intent> rows = jdbc.query("SELECT request_hmac,object_kind,object_id FROM m2.g5_creation_intent WHERE actor_id=? AND method=? AND path=? AND request_key=?", (row, i) -> new Intent(row.getString(1), row.getString(2), row.getObject(3, UUID.class)), actor, method, path, key);
        return rows.isEmpty() ? null : rows.getFirst();
    }
    public void remember(UUID actor, String method, String path, UUID key, String digest, String response, int status, String kind, UUID id) {
        jdbc.update("INSERT INTO m2.g5_idempotency(actor_id,method,path,request_key,request_hmac,response,status) VALUES (?,?,?,?,?,CAST(? AS jsonb),?) ON CONFLICT(actor_id,method,path,request_key) DO UPDATE SET request_hmac=EXCLUDED.request_hmac,response=EXCLUDED.response,status=EXCLUDED.status,created_at=CURRENT_TIMESTAMP,expires_at=CURRENT_TIMESTAMP+interval '24 hours'", actor, method, path, key, digest, response, status);
        if (kind != null) jdbc.update("INSERT INTO m2.g5_creation_intent(actor_id,method,path,request_key,request_hmac,object_kind,object_id) VALUES (?,?,?,?,?,?,?)", actor, method, path, key, digest, kind, id);
    }
}
