package com.liming.rag.identity.application;

import cn.dev33.satoken.secure.BCrypt;
import cn.dev33.satoken.stp.StpUtil;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 登录、退出、当前用户和改自己的密码。会话由 Sa-Token 管理（试点存内存，见 D-005）；租户在登录时写进会话。
 * 同一账号连续输错 5 次密码锁 15 分钟，按账号锁、不按 IP 锁（门店常共用网络，D-038）。
 */
@Service
public class AuthService {

    static final int MAX_FAILED_ATTEMPTS = 5;
    static final Duration LOCK_DURATION = Duration.ofMinutes(15);
    /** 用户名不存在时也做一次 BCrypt 比对，避免从响应时间判断账号是否存在。 */
    private static final String DUMMY_HASH = BCrypt.hashpw("dummy-password", BCrypt.gensalt());

    private final Accounts accounts;
    private final Roles roles;
    private final AccessService access;
    private final Clock clock;

    @Autowired
    AuthService(Accounts accounts, Roles roles, AccessService access) {
        this(accounts, roles, access, Clock.systemUTC());
    }

    AuthService(Accounts accounts, Roles roles, AccessService access, Clock clock) {
        this.accounts = accounts;
        this.roles = roles;
        this.access = access;
        this.clock = clock;
    }

    /**
     * 登录成功返回令牌。用户名不存在、密码错误、账号停用都给同一个提示；锁定时说明还要等多久。
     * 不加事务：密码错误的计数要在抛出异常后仍然保存。
     */
    public SignIn login(String username, String password) {
        var found = accounts.findByUsername(username == null ? "" : username.strip());
        boolean matches = BCrypt.checkpw(password == null ? "" : password,
                found.map(Account::passwordHash).orElse(DUMMY_HASH));
        if (found.isEmpty()) {
            throw badCredentials();
        }
        var account = found.get();
        var now = OffsetDateTime.now(clock);
        if (account.lockedAt(now)) {
            throw locked(account.lockedUntil(), now);
        }
        if (!matches) {
            int attempts = account.failedAttempts() + 1;
            if (attempts >= MAX_FAILED_ATTEMPTS) {
                var until = now.plus(LOCK_DURATION);
                accounts.recordFailedLogin(account.id(), 0, until);
                throw locked(until, now);
            }
            accounts.recordFailedLogin(account.id(), attempts, null);
            throw badCredentials();
        }
        if (!account.enabled()) {
            throw badCredentials();
        }
        if (account.failedAttempts() > 0 || account.lockedUntil() != null) {
            accounts.clearFailedLogins(account.id());
        }
        StpUtil.login(account.id());
        StpUtil.getSession().set(AccessService.TENANT_KEY, account.tenantId().value());
        return new SignIn(StpUtil.getTokenValue(), me(account));
    }

    public void logout() {
        StpUtil.logout();
    }

    public Me me() {
        return me(current());
    }

    /** 改自己的密码，要输入旧密码；改完不再要求“必须先改密码”，当前会话保留。 */
    @Transactional
    public void changePassword(String oldPassword, String newPassword) {
        var account = current();
        if (!BCrypt.checkpw(oldPassword == null ? "" : oldPassword, account.passwordHash())) {
            throw new IdentityException(IdentityException.Reason.INVALID, "当前密码不对");
        }
        PasswordRules.check(account.username(), newPassword);
        accounts.setPassword(account.id(), BCrypt.hashpw(newPassword, BCrypt.gensalt()), false);
    }

    private Account current() {
        return accounts.find(StpUtil.getLoginIdAsLong()).orElseThrow(AuthService::badCredentials);
    }

    private Me me(Account account) {
        var assigned = roles.rolesOf(account.id());
        return new Me(account, assigned, access.permissionsOf(account), assigned.stream().anyMatch(Role::superAdmin));
    }

    private static IdentityException badCredentials() {
        return new IdentityException(IdentityException.Reason.BAD_CREDENTIALS, "用户名或密码错误");
    }

    private static IdentityException locked(OffsetDateTime until, OffsetDateTime now) {
        long minutes = Math.max(1, (Duration.between(now, until).toSeconds() + 59) / 60);
        return new IdentityException(IdentityException.Reason.LOCKED,
                "密码连续输错 " + MAX_FAILED_ATTEMPTS + " 次，账号已锁定，请 " + minutes + " 分钟后再试或联系管理员解锁");
    }

    public record SignIn(String token, Me user) {
    }

    /** 当前登录的账号、角色和权限点，供前端显示菜单和按钮。 */
    public record Me(Account account, List<Role> roles, List<String> permissions, boolean superAdmin) {
    }
}
