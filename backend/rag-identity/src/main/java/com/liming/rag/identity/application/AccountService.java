package com.liming.rag.identity.application;

import cn.dev33.satoken.secure.BCrypt;
import cn.dev33.satoken.stp.StpInterface;
import cn.dev33.satoken.stp.StpUtil;
import com.liming.rag.common.TenantId;
import com.liming.rag.common.UserId;
import com.liming.rag.identity.api.CurrentUser;
import com.liming.rag.identity.api.Roles;
import com.liming.rag.identity.api.UserDirectory;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 账号与登录。会话由 Sa-Token 管理（试点存内存，见 D-005）；租户在登录时写进会话，之后每个请求从会话取。
 * 同时实现 Sa-Token 的角色查询：每次校验都读账号表，停用或改角色立即生效。
 */
@Service
public class AccountService implements CurrentUser, UserDirectory, StpInterface {

    private static final String TENANT_KEY = "tenantId";
    private static final Pattern USERNAME = Pattern.compile("[A-Za-z0-9_.-]{3,50}");
    private static final Set<String> ROLES = Set.of(Roles.ADMIN, Roles.STAFF);
    /** 用户名不存在时也做一次 BCrypt 比对，避免从响应时间判断账号是否存在。 */
    private static final String DUMMY_HASH = BCrypt.hashpw("dummy-password", BCrypt.gensalt());

    private final Accounts accounts;

    AccountService(Accounts accounts) {
        this.accounts = accounts;
    }

    /** 登录成功返回令牌；用户名不存在、密码错误、账号停用都给同一个提示。 */
    public SignIn login(String username, String password) {
        var account = accounts.findByUsername(username == null ? "" : username.strip());
        boolean matches = BCrypt.checkpw(password == null ? "" : password,
                account.map(Account::passwordHash).orElse(DUMMY_HASH));
        if (account.isEmpty() || !matches || !account.get().enabled()) {
            throw new IdentityException(IdentityException.Reason.BAD_CREDENTIALS, "用户名或密码错误");
        }
        StpUtil.login(account.get().id());
        StpUtil.getSession().set(TENANT_KEY, account.get().tenantId().value());
        return new SignIn(StpUtil.getTokenValue(), account.get());
    }

    public void logout() {
        StpUtil.logout();
    }

    @Transactional
    public Account create(TenantId tenantId, String username, String displayName, String password, String role) {
        var name = username == null ? "" : username.strip();
        var display = displayName == null ? "" : displayName.strip();
        if (!USERNAME.matcher(name).matches()) {
            throw invalid("用户名必须是 3 到 50 个字母、数字或 _ . -");
        }
        if (display.isEmpty() || display.length() > 50) {
            throw invalid("显示名必须是 1 到 50 个字符");
        }
        if (password == null || password.length() < 8 || password.length() > 64) {
            throw invalid("密码必须是 8 到 64 个字符");
        }
        if (!ROLES.contains(role)) {
            throw invalid("角色只能是 ADMIN 或 STAFF");
        }
        if (accounts.existsByUsername(name)) {
            throw new IdentityException(IdentityException.Reason.CONFLICT, "用户名已被使用：" + name);
        }
        return accounts.insert(new Account(null, tenantId, name, display, BCrypt.hashpw(password, BCrypt.gensalt()),
                role, true));
    }

    public Account me() {
        return accounts.find(StpUtil.getLoginIdAsLong())
                .orElseThrow(() -> new IdentityException(IdentityException.Reason.BAD_CREDENTIALS, "账号不存在"));
    }

    @Override
    public UserId userId() {
        return new UserId(StpUtil.getLoginIdAsLong());
    }

    @Override
    public TenantId tenantId() {
        return new TenantId(StpUtil.getSession().getLong(TENANT_KEY));
    }

    @Override
    public boolean exists(TenantId tenantId, UserId userId) {
        return accounts.find(userId.value())
                .filter(account -> account.enabled() && account.tenantId().equals(tenantId))
                .isPresent();
    }

    @Override
    public List<String> getRoleList(Object loginId, String loginType) {
        return accounts.find(Long.parseLong(loginId.toString()))
                .filter(Account::enabled)
                .map(account -> List.of(account.role()))
                .orElse(List.of());
    }

    @Override
    public List<String> getPermissionList(Object loginId, String loginType) {
        return List.of();
    }

    private static IdentityException invalid(String message) {
        return new IdentityException(IdentityException.Reason.INVALID, message);
    }

    public record SignIn(String token, Account account) {
    }
}
