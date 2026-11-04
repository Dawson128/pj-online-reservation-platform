package cn.edu.campus.approval.g5.application;

import java.util.UUID;
import java.util.function.Supplier;

import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;

import cn.edu.campus.approval.g5.api.IdentityException;
import cn.edu.campus.approval.g5.api.Principal;
import cn.edu.campus.approval.g5.domain.Credentials;
import cn.edu.campus.approval.g5.persistence.AuthAudit;
import cn.edu.campus.approval.g5.persistence.AuthRateLimiter;
import cn.edu.campus.approval.g5.persistence.IdentityRepository;
import cn.edu.campus.approval.g5.persistence.IdentityRepository.Account;
import cn.edu.campus.approval.g5.persistence.IdentityRepository.ActiveSession;

@org.springframework.context.annotation.Profile("!shell")
@Service
public class IdentityService {
    private final IdentityRepository identities;
    private final AuthRateLimiter limiter;
    private final AuthAudit audit;
    private final Credentials credentials;
    private final TransactionTemplate transaction;
    private final TransactionTemplate readSnapshot;

    public IdentityService(IdentityRepository identities, AuthRateLimiter limiter, AuthAudit audit,
                           Credentials credentials, TransactionTemplate transaction) {
        this.identities = identities;
        this.limiter = limiter;
        this.audit = audit;
        this.credentials = credentials;
        this.transaction = transaction;
        this.readSnapshot = new TransactionTemplate(transaction.getTransactionManager());
        this.readSnapshot.setReadOnly(true);
        this.readSnapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }
    public record Login(Principal principal, String sessionToken, String csrfToken) {
        @Override public String toString() { return "Login[credentials=REDACTED]"; }
    }
    public static void requireSystemManager(Principal principal) {
        if (principal == null) throw Credentials.unauthenticated();
        boolean allowed = principal.grants().stream().anyMatch(grant -> grant.action().equals("system:manage") && "SYSTEM".equals(grant.scope().get("kind")));
        if (!allowed) throw new IdentityException(403,"FORBIDDEN","需要系统管理权限");
    }
    private record Outcome<T>(T value, IdentityException error) { }

    public Login login(String account, String password, String ip, UUID trace) {
        String code = Credentials.text(account, 1, 40, "account", true);
        String secret = Credentials.text(password, 1, 128, "password", false);
        return transact(() -> {
            var rate = limiter.lock("LOGIN", code, ip, identities.now());
            if (rate.retryAfter() > 0) { return refused(null, "auth:login", limited(rate.retryAfter()), trace); }
            Account found = identities.lockAccountByCode(code);
            boolean correct = credentials.matches(secret, found == null ? null : found.passwordHash());
            if (!correct || found == null || !found.enabled()) {
                limiter.failed(rate);
                return refused(found, "auth:login", Credentials.unauthenticated(), trace);
            }
            String token = credentials.token();
            String csrf = credentials.token();
            ActiveSession session = identities.createSession(found, token, csrf);
            audit.record("auth:login", found.id(), found.version(), "SUCCESS", null, trace, false);
            return new Outcome<>(new Login(identities.principal(session), token, csrf), null);
        });
    }
    public Principal introspect(String token) {
        try {
            return readSnapshot.execute(status -> {
                ActiveSession session = identities.activeSession(token);
                if (session == null) { throw Credentials.unauthenticated(); }
                return identities.principal(session);
            });
        } catch (DataAccessException | TransactionException unavailable) { throw unavailable(); }
    }
    public void requireCsrf(String token, String csrf) {
        try {
            ActiveSession session = identities.activeSession(token);
            if (session == null) { throw Credentials.unauthenticated(); }
            if (csrf == null || csrf.length() > 128 || !Credentials.same(session.csrfHash(), Credentials.hash(csrf))) {
                throw new IdentityException(403, "FORBIDDEN", "安全校验失败，请重新登录后重试");
            }
        } catch (DataAccessException | TransactionException unavailable) { throw unavailable(); }
    }
    public void logout(String token, String csrf, UUID trace) {
        transact(() -> {
            ActiveSession session = identities.activeSession(token);
            if (session == null) { return new Outcome<>(Boolean.TRUE, null); }
            if (csrf == null || csrf.length() > 128 || !Credentials.same(session.csrfHash(), Credentials.hash(csrf))) {
                return refused(session.account(), "auth:logout", new IdentityException(403, "FORBIDDEN", "安全校验失败"), trace);
            }
            identities.revokeSession(session.id());
            audit.record("auth:logout", session.account().id(), session.account().version(), "SUCCESS", null, trace, false);
            return new Outcome<>(Boolean.TRUE, null);
        });
    }
    public void changePassword(String token, String csrf, String current, String replacement,
                               String expectedVersion, String ip, UUID trace) {
        String oldPassword = Credentials.text(current, 1, 128, "currentPassword", false);
        String newPassword = Credentials.text(replacement, 8, 24, "newPassword", false);
        if (oldPassword.equals(newPassword)) { throw Credentials.invalid("newPassword", "新密码不能与当前密码相同"); }
        long expected;
        try {
            if (expectedVersion == null || !expectedVersion.matches("[1-9][0-9]{0,18}")) { throw new NumberFormatException(); }
            expected = Long.parseLong(expectedVersion);
        } catch (NumberFormatException bad) { throw Credentials.invalid("expectedVersion", "版本须为正整数的十进制字符串"); }
        transact(() -> {
            ActiveSession session = identities.activeSession(token);
            if (session == null) { return new Outcome<>(null, Credentials.unauthenticated()); }
            Account account = identities.lockAccount(session.account().id());
            session = identities.activeSession(token);
            if (session == null) { return new Outcome<>(null, Credentials.unauthenticated()); }
            if (csrf == null || csrf.length() > 128 || !Credentials.same(session.csrfHash(), Credentials.hash(csrf))) {
                return refused(account, "auth:change-password", new IdentityException(403, "FORBIDDEN", "安全校验失败"), trace);
            }
            var rate = limiter.lock("CHANGE", account.id().toString(), ip, identities.now());
            if (rate.retryAfter() > 0) { return refused(account, "auth:change-password", limited(rate.retryAfter()), trace); }
            if (account.version() != expected) {
                return refused(account, "auth:change-password", new IdentityException(409, "VERSION_CONFLICT",
                        "账户已发生变化，请刷新后重试", null, Long.toString(account.version()), null), trace);
            }
            if (!credentials.matches(oldPassword, account.passwordHash())) {
                limiter.failed(rate);
                return refused(account, "auth:change-password", new IdentityException(403, "CURRENT_PASSWORD_INVALID", "当前密码不正确"), trace);
            }
            identities.changePassword(account, credentials.passwordHash(newPassword));
            audit.record("auth:change-password", account.id(), account.version() + 1, "SUCCESS", null, trace, true);
            return new Outcome<>(Boolean.TRUE, null);
        });
    }
    public void recordRejection(String action, Principal actor, String code, UUID trace) {
        try {
            transaction.executeWithoutResult(status -> audit.record(action, actor == null ? null : actor.userId(),
                    actor == null ? 1 : Long.parseLong(actor.accountVersion()), "FAILURE", code, trace, false));
        } catch (DataAccessException | TransactionException unavailable) { throw unavailable(); }
    }
    private <T> Outcome<T> refused(Account account, String action, IdentityException failure, UUID trace) {
        audit.record(action, account == null ? null : account.id(), account == null ? 1 : account.version(),
                "FAILURE", failure.code(), trace, false);
        return new Outcome<>(null, failure);
    }
    private <T> T transact(Supplier<Outcome<T>> command) {
        Outcome<T> result;
        try { result = transaction.execute(status -> command.get()); }
        catch (DataAccessException | TransactionException unavailable) { throw unavailable(); }
        if (result == null) { throw unavailable(); }
        if (result.error() != null) { throw result.error(); }
        return result.value();
    }
    private static IdentityException limited(int retry) {
        return new IdentityException(429, "RATE_LIMITED", "尝试次数过多，请稍后重试", null, null, retry);
    }
    private static IdentityException unavailable() {
        return new IdentityException(503, "AUTH_UNAVAILABLE", "身份服务暂不可用，请稍后重试");
    }
}
