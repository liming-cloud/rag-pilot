package com.liming.rag.identity.application.auth;

import com.liming.rag.identity.application.account.Account;
import com.liming.rag.identity.application.account.AccountRules;
import com.liming.rag.identity.application.account.Accounts;
import com.liming.rag.identity.application.role.AccessService;
import com.liming.rag.identity.application.role.Role;
import com.liming.rag.identity.application.role.Roles;
import com.liming.rag.identity.application.IdentityException;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 登录、退出、当前用户和改自己的密码。会话经 {@link Sessions}（Sa-Token，试点存内存，D-005），租户在登录时写进会话。
 * 连续输错密码的锁定规则见 {@link AccountRules}（D-038）。
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final Accounts accounts;
    private final Roles roles;
    private final AccessService access;
    private final Sessions sessions;
    private final PasswordHasher passwords;
    private final Clock clock;
    /** 用户名不存在时也比对一次摘要，避免从响应时间判断账号是否存在。 */
    private final String dummyHash;

    AuthService(Accounts accounts, Roles roles, AccessService access, Sessions sessions, PasswordHasher passwords,
            Clock clock) {
        this.accounts = accounts;
        this.roles = roles;
        this.access = access;
        this.sessions = sessions;
        this.passwords = passwords;
        this.clock = clock;
        this.dummyHash = passwords.hash("dummy-password");
    }

    /**
     * 登录成功返回令牌。用户名不存在、密码错误、账号停用都给同一个提示；锁定时说明还要等多久。
     * 不加事务：密码错误的计数要在抛出异常后仍然保存。
     */
    public SignInResult login(String username, String password) {
        var found = accounts.findByUsername(username == null ? "" : username.strip());
        boolean matches = passwords.matches(password, found.map(Account::passwordHash).orElse(dummyHash));
        if (found.isEmpty()) {
            loginFailed(username, "unknown_user");
            throw badCredentials();
        }
        var account = found.get();
        var now = OffsetDateTime.now(clock);
        if (account.lockedAt(now)) {
            loginFailed(account.username(), "locked");
            throw locked(account.lockedUntil(), now);
        }
        if (!matches) {
            int attempts = accounts.incrementFailedLogins(account.id());
            var lockedUntil = AccountRules.lockedUntil(attempts, now);
            loginFailed(account.username(), "bad_password", attempts);
            if (lockedUntil == null) {
                throw badCredentials();
            }
            accounts.lock(account.id(), lockedUntil);
            log.warn("event=account_locked username={} until={}", account.username(), lockedUntil);
            throw locked(lockedUntil, now);
        }
        if (!account.enabled()) {
            loginFailed(account.username(), "disabled");
            throw badCredentials();
        }
        if (account.failedAttempts() > 0 || account.lockedUntil() != null) {
            accounts.clearFailedLogins(account.id());
        }
        return new SignInResult(sessions.login(account.id(), account.tenantId()), me(account));
    }

    /** 登录失败留一行日志（#145 C1）；不合规的用户名不原样写进日志，防止伪造日志行。 */
    private static void loginFailed(String username, String reason) {
        log.warn("event=login_failed username={} reason={}", shown(username), reason);
    }

    /** 密码错误时带上连续输错的次数。 */
    private static void loginFailed(String username, String reason, int attempts) {
        log.warn("event=login_failed username={} reason={} attempts={}", shown(username), reason, attempts);
    }

    private static String shown(String username) {
        return username != null && AccountRules.USERNAME.matcher(username.strip()).matches() ? username.strip()
                : "(不合规)";
    }

    public void logout() {
        sessions.logoutCurrent();
    }

    public Profile me() {
        return me(current());
    }

    /** 改自己的密码，要输入旧密码；改完不再要求“必须先改密码”，当前会话保留。 */
    @Transactional
    public void changePassword(String oldPassword, String newPassword) {
        var account = current();
        if (!passwords.matches(oldPassword, account.passwordHash())) {
            throw new IdentityException(IdentityException.Reason.INVALID, "当前密码不对");
        }
        PasswordRules.check(account.username(), newPassword);
        accounts.setPassword(account.id(), passwords.hash(newPassword), false);
        access.forget(account.id());
    }

    private Account current() {
        return accounts.find(sessions.currentAccountId()).orElseThrow(AuthService::badCredentials);
    }

    private Profile me(Account account) {
        var assigned = roles.rolesOf(account.id());
        return new Profile(account, assigned, access.permissionsOf(account), assigned.stream().anyMatch(Role::superAdmin));
    }

    private static IdentityException badCredentials() {
        return new IdentityException(IdentityException.Reason.BAD_CREDENTIALS, "用户名或密码错误");
    }

    private static IdentityException locked(OffsetDateTime until, OffsetDateTime now) {
        long minutes = Math.max(1, (Duration.between(now, until).toSeconds() + 59) / 60);
        return new IdentityException(IdentityException.Reason.LOCKED,
                "密码连续输错 " + AccountRules.MAX_FAILED_ATTEMPTS + " 次，账号已锁定，请 " + minutes + " 分钟后再试或联系管理员解锁");
    }

}
