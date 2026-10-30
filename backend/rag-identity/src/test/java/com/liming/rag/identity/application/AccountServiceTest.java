package com.liming.rag.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.dev33.satoken.secure.BCrypt;
import com.liming.rag.identity.api.Roles;
import com.liming.rag.shared.ErrorKind;
import com.liming.rag.shared.TenantId;
import com.liming.rag.shared.UserId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** 账号规则。登录成功和会话由 Sa-Token 管理，在 rag-app 的 AuthTests 里验证；这里只测不依赖会话的规则。 */
class AccountServiceTest {

    private static final TenantId TENANT = new TenantId(1);
    private static final TenantId OTHER_TENANT = new TenantId(2);

    private final InMemoryAccounts accounts = new InMemoryAccounts();
    private final AccountService service = new AccountService(accounts);

    @Test
    @DisplayName("新建账号：去掉首尾空格，密码只存 BCrypt 摘要，默认启用")
    void createsAccount() {
        var created = service.create(TENANT, "  staff01 ", " 店员小王 ", "staff-password", Roles.STAFF);

        assertThat(created.username()).isEqualTo("staff01");
        assertThat(created.displayName()).isEqualTo("店员小王");
        assertThat(created.enabled()).isTrue();
        assertThat(created.passwordHash()).isNotEqualTo("staff-password");
        assertThat(BCrypt.checkpw("staff-password", created.passwordHash())).isTrue();
    }

    @ParameterizedTest(name = "{4}")
    @CsvSource(delimiter = '|', textBlock = """
            ab       | 店员 | staff-password | STAFF | 用户名太短
            店员01   | 店员 | staff-password | STAFF | 用户名含中文
            staff01  |      | staff-password | STAFF | 显示名为空
            staff01  | 店员 | short          | STAFF | 密码太短
            staff01  | 店员 | staff-password | OWNER | 角色不认识
            """)
    @DisplayName("新建账号的输入不合规时拒绝，不写入")
    void rejectsInvalidInput(String username, String displayName, String password, String role, String reason) {
        assertThatThrownBy(() -> service.create(TENANT, username, displayName, password, role))
                .isInstanceOf(IdentityException.class)
                .extracting("kind").isEqualTo(ErrorKind.INVALID);
        assertThat(accounts.saved).isEmpty();
    }

    @Test
    @DisplayName("用户名已被使用时返回冲突")
    void rejectsDuplicateUsername() {
        service.create(TENANT, "staff01", "店员", "staff-password", Roles.STAFF);

        assertThatThrownBy(() -> service.create(TENANT, "staff01", "重名", "staff-password", Roles.STAFF))
                .isInstanceOf(IdentityException.class)
                .extracting("kind").isEqualTo(ErrorKind.CONFLICT);
        assertThat(accounts.saved).hasSize(1);
    }

    @Test
    @DisplayName("不能停用自己，避免把唯一的管理员锁在外面")
    void cannotDisableSelf() {
        var admin = service.create(TENANT, "admin01", "管理员", "admin-password", Roles.ADMIN);
        var self = new UserId(admin.id());

        assertThatThrownBy(() -> service.setEnabled(TENANT, self, self, false))
                .extracting("kind").isEqualTo(ErrorKind.INVALID);
        assertThat(accounts.find(admin.id()).orElseThrow().enabled()).isTrue();
    }

    @Test
    @DisplayName("其他租户的账号当作不存在，不能启用或停用")
    void otherTenantIsNotFound() {
        var other = service.create(OTHER_TENANT, "staff01", "店员", "staff-password", Roles.STAFF);

        assertThatThrownBy(() -> service.setEnabled(TENANT, new UserId(other.id()), new UserId(99), true))
                .extracting("kind").isEqualTo(ErrorKind.NOT_FOUND);
    }

    @Test
    @DisplayName("用户名不存在、密码错误、账号停用都给同一个提示，且不建立会话")
    void badCredentialsLookTheSame() {
        var staff = service.create(TENANT, "staff01", "店员", "staff-password", Roles.STAFF);
        service.create(TENANT, "leaver01", "已离职", "staff-password", Roles.STAFF);
        accounts.setEnabled(accounts.findByUsername("leaver01").orElseThrow().id(), false);

        var unknown = catchLogin("nobody", "staff-password");
        var wrongPassword = catchLogin(staff.username(), "wrong-password");
        var disabled = catchLogin("leaver01", "staff-password");

        assertThat(List.of(unknown, wrongPassword, disabled)).allSatisfy(error -> {
            assertThat(error).isInstanceOf(IdentityException.class);
            assertThat(((IdentityException) error).kind()).isEqualTo(ErrorKind.UNAUTHENTICATED);
            assertThat(error).hasMessage("用户名或密码错误");
        });
    }

    @Test
    @DisplayName("角色每次从账号表读：停用后没有任何角色；目录只认本租户启用的账号")
    void rolesAndDirectoryFollowAccountState() {
        var staff = service.create(TENANT, "staff01", "店员", "staff-password", Roles.STAFF);
        assertThat(service.getRoleList(staff.id(), "login")).containsExactly(Roles.STAFF);
        assertThat(service.exists(TENANT, new UserId(staff.id()))).isTrue();
        assertThat(service.exists(OTHER_TENANT, new UserId(staff.id()))).isFalse();

        accounts.setEnabled(staff.id(), false);

        assertThat(service.getRoleList(staff.id(), "login")).isEmpty();
        assertThat(service.exists(TENANT, new UserId(staff.id()))).isFalse();
    }

    private Throwable catchLogin(String username, String password) {
        try {
            service.login(username, password);
            return null;
        } catch (RuntimeException e) {
            return e;
        }
    }

    /** 账号存储的内存实现，只用于本测试。 */
    private static final class InMemoryAccounts implements Accounts {

        final List<Account> saved = new ArrayList<>();

        @Override
        public Optional<Account> find(long id) {
            return saved.stream().filter(account -> account.id() == id).findFirst();
        }

        @Override
        public Optional<Account> findByUsername(String username) {
            return saved.stream().filter(account -> account.username().equals(username)).findFirst();
        }

        @Override
        public boolean existsByUsername(String username) {
            return findByUsername(username).isPresent();
        }

        @Override
        public List<Account> list(TenantId tenantId) {
            return saved.stream().filter(account -> account.tenantId().equals(tenantId)).toList();
        }

        @Override
        public long count() {
            return saved.size();
        }

        @Override
        public void setEnabled(long id, boolean enabled) {
            var account = find(id).orElseThrow();
            saved.set(saved.indexOf(account), new Account(account.id(), account.tenantId(), account.username(),
                    account.displayName(), account.passwordHash(), account.role(), enabled));
        }

        @Override
        public Account insert(Account account) {
            var stored = new Account((long) saved.size() + 1, account.tenantId(), account.username(),
                    account.displayName(), account.passwordHash(), account.role(), account.enabled());
            saved.add(stored);
            return stored;
        }
    }
}
