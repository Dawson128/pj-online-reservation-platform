package cn.edu.campus.approval.g5.application;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import cn.edu.campus.approval.g5.domain.Credentials;
import cn.edu.campus.approval.g5.domain.PermissionCatalog;
import cn.edu.campus.approval.g5.persistence.AuthAudit;

@org.springframework.context.annotation.Profile("!shell")
@Component
public class IdentitySeed implements ApplicationRunner {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final Environment environment;
    private final Credentials credentials;
    private final AuthAudit audit;
    private static final Map<String, List<String>> ROLES = new LinkedHashMap<>();
    static {
        ROLES.put("STUDENT", List.of("room:read", "reservation:create", "reservation:read-own", "reservation:cancel",
                "reservation:checkin", "violation:read-own", "violation:appeal", "workorder:report",
                "workorder:read-own", "workorder:cancel", "workorder:evaluate"));
        ROLES.put("APPLICANT", List.of("room:read", "room:apply", "application:read-own", "application:withdraw"));
        ROLES.put("ROOM_ADMIN", List.of("room:read", "room:manage", "room:display", "approval:level1",
                "application:read", "workorder:read", "workorder:assign", "workorder:accept", "resource:trace"));
        ROLES.put("OPERATIONS_ADMIN", List.of("room:read", "approval:level2", "application:read", "application:terminate",
                "statistics:read", "violation:read", "violation:review", "violation:correct", "workorder:read",
                "workorder:escalation-receive", "resource:trace"));
        ROLES.put("REPAIRER", List.of("workorder:read-assigned", "workorder:repair"));
        ROLES.put("SYSTEM_ADMIN", List.of("system:manage", "audit:read", "operation:read", "operation:recover",
                "notification:read", "notification:retry", "runtime:read"));
    }
    private static final Map<String, String> NAMES = Map.of("STUDENT", "学生", "APPLICANT", "整室申请人",
            "ROOM_ADMIN", "教室管理员", "OPERATIONS_ADMIN", "运营管理员", "REPAIRER", "维修人员", "SYSTEM_ADMIN", "系统管理员");

    public IdentitySeed(JdbcTemplate jdbc, TransactionTemplate transaction, Environment environment,
                        Credentials credentials, AuthAudit audit) {
        this.jdbc = jdbc;
        this.transaction = transaction;
        this.environment = environment;
        this.credentials = credentials;
        this.audit = audit;
    }
    @Override
    public void run(ApplicationArguments arguments) {
        transaction.executeWithoutResult(status -> {
            jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtext('G5_IDENTITY_SEED'))");
            for (var permission : PermissionCatalog.ALL) {
                jdbc.update("INSERT INTO m2.permission(action,description,owner_group) VALUES (?,?,?) ON CONFLICT(action) DO UPDATE SET description=EXCLUDED.description,owner_group=EXCLUDED.owner_group",
                        permission.action(), permission.description(), permission.ownerGroup());
            }
            for (var entry : ROLES.entrySet()) {
                UUID role = id("role:" + entry.getKey());
                int inserted = jdbc.update("INSERT INTO m2.role(id,key,name,built_in) VALUES (?,?,?,true) ON CONFLICT(key) DO NOTHING",
                        role, entry.getKey(), NAMES.get(entry.getKey()));
                // Existing role grants and names are owned by subsequent administrator changes.
                if (inserted == 1) {
                    for (String action : entry.getValue()) {
                        jdbc.update("INSERT INTO m2.role_grant(id,role_id,action,scope_kind) VALUES (?,?,?,?)",
                                id(entry.getKey() + ":" + action), role, action,
                                entry.getKey().equals("SYSTEM_ADMIN") ? "SYSTEM" : "SCHOOL");
                    }
                }
            }
            if (environment.getProperty("G5_BOOTSTRAP_ENABLED", Boolean.class, false)) { bootstrap(); }
        });
    }
    private void bootstrap() {
        String code = Credentials.text(environment.getProperty("G5_BOOTSTRAP_ACCOUNT", "admin"), 1, 40, "bootstrapAccount", true);
        if (jdbc.queryForObject("SELECT count(*) FROM m2.account WHERE user_code=?", Long.class, code) != 0) { return; }
        // Once any account exists, the first-deployment bootstrap cannot create a new administrator under a changed name.
        if (jdbc.queryForObject("SELECT count(*) FROM m2.account", Long.class) != 0) {
            throw new IllegalStateException("Initial administrator already established; use audited account management");
        }
        String password = Credentials.text(environment.getProperty("G5_BOOTSTRAP_PASSWORD"), 8, 24, "bootstrapPassword", false);
        UUID department = id("department:SYSTEM");
        jdbc.update("INSERT INTO m2.department(id,code,name) VALUES (?,'SYSTEM','系统管理') ON CONFLICT(code) DO NOTHING", department);
        UUID actualDepartment = jdbc.queryForObject("SELECT id FROM m2.department WHERE code='SYSTEM'", UUID.class);
        UUID account = id("account:" + code);
        jdbc.update("""
                INSERT INTO m2.account(id,user_code,display_name,department_id,email,password_hash)
                VALUES (?,?,'初始管理员',?,'admin@example.invalid',?)
                """, account, code, actualDepartment, credentials.passwordHash(password));
        jdbc.update("INSERT INTO m2.role_binding(id,account_id,role_id) VALUES (?,?,?)",
                id("binding:" + code), account, id("role:SYSTEM_ADMIN"));
        audit.bootstrap(account, UUID.randomUUID());
    }
    private static UUID id(String key) { return UUID.nameUUIDFromBytes(("G5:" + key).getBytes(StandardCharsets.UTF_8)); }
}
