package com.liming.rag.identity.application;

import static com.liming.rag.identity.application.IdentityFixture.TENANT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.liming.rag.identity.application.AccountImportService.Problem;
import com.liming.rag.identity.application.AccountImportService.Row;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 账号批量导入（#124、D-046）：逐行预检、全部通过才导入、导入的账号必须先改密码、不能越权分配。 */
class AccountImportServiceTest {

    private final IdentityFixture fixture = new IdentityFixture();
    private final AccountImportService service = new AccountImportService(fixture.store, fixture.store,
            fixture.access, fixture.passwords);

    private static Row row(int line, String username, String displayName, String... roles) {
        return new Row(line, username, displayName, List.of(roles));
    }

    @Test
    @DisplayName("合法的行全部导入：去掉首尾空格，按角色名分配角色，都必须先改密码")
    void importsAll() {
        var imported = service.importAll(TENANT, fixture.admin, List.of(
                row(2, " new01 ", " 新员工一 ", "员工"),
                row(3, "new02", "新员工二", "员工", " 员工 ")), "Welcome-2026");

        assertThat(imported).isEqualTo(2);
        var first = fixture.store.findByUsername("new01").orElseThrow();
        assertThat(first.displayName()).isEqualTo("新员工一");
        assertThat(first.mustChangePassword()).isTrue();
        assertThat(fixture.passwords.matches("Welcome-2026", first.passwordHash())).isTrue();
        assertThat(fixture.store.rolesOf(first.id())).containsExactly(fixture.staffRole);
        var second = fixture.store.findByUsername("new02").orElseThrow();
        assertThat(second.mustChangePassword()).isTrue();
        assertThat(fixture.store.rolesOf(second.id())).containsExactly(fixture.staffRole);
    }

    @Test
    @DisplayName("预检逐行列出全部问题：格式、文件内重复、已存在、显示名、角色不存在、没有角色")
    void listsEveryProblem() {
        var problems = service.check(TENANT, fixture.admin, List.of(
                row(2, "ok01", "没问题", "员工"),
                row(3, "admin01", "已存在", "员工"),
                row(4, "ok01", "文件里重复", "员工"),
                row(5, "x", "", "不存在的角色"),
                row(6, "norole", "没有角色")));

        assertThat(problems).containsExactly(
                new Problem(3, "用户名已存在"),
                new Problem(4, "与第 2 行的用户名重复"),
                new Problem(5, "用户名必须是 3 到 50 个字母、数字或 _ . -"),
                new Problem(5, "显示名必须是 1 到 50 个字符"),
                new Problem(5, "没有叫“不存在的角色”的角色"),
                new Problem(6, "至少要有一个角色"));
        assertThat(fixture.store.existsByUsername("ok01")).isFalse();
    }

    @Test
    @DisplayName("不能分配超出自己权限的角色、超级管理员角色；超级管理员导入时不受自己权限限制")
    void cannotEscalate() {
        fixture.role("审计", TestCatalog.MODEL_VIEW);
        var rows = List.of(row(2, "boss", "老板", "超级管理员"), row(3, "audit01", "审计员", "审计"));

        assertThat(service.check(TENANT, fixture.admin, rows)).containsExactly(
                new Problem(2, "超级管理员角色不能分配"),
                new Problem(3, "不能分配超出自己权限的角色：审计"));
        assertThat(service.check(TENANT, fixture.superAdmin, rows)).containsExactly(
                new Problem(2, "超级管理员角色不能分配"));
    }

    @Test
    @DisplayName("任一行有问题时一条都不写入")
    void writesNothingWhenAnyRowFails() {
        assertThatThrownBy(() -> service.importAll(TENANT, fixture.admin, List.of(
                row(2, "good01", "好的", "员工"),
                row(3, "admin01", "已存在", "员工")), "Welcome-2026"))
                .isInstanceOf(IdentityException.class).hasMessageContaining("1 行有问题");

        assertThat(fixture.store.existsByUsername("good01")).isFalse();
    }

    @Test
    @DisplayName("初始密码不合规或与某行用户名相同时不导入")
    void checksInitialPassword() {
        assertThatThrownBy(() -> service.importAll(TENANT, fixture.admin, List.of(row(2, "new01", "新", "员工")),
                "short")).hasMessageContaining("8 到 64");
        assertThatThrownBy(() -> service.importAll(TENANT, fixture.admin,
                List.of(row(2, "welcome2026", "新", "员工")), "Welcome2026")).hasMessageContaining("1 行有问题");
        assertThat(service.check(TENANT, fixture.admin, List.of(row(2, "welcome2026", "新", "员工")))).isEmpty();
        assertThat(fixture.store.existsByUsername("new01")).isFalse();
    }

    @Test
    @DisplayName("没有行或超过 500 行时拒绝")
    void limitsRows() {
        assertThatThrownBy(() -> service.check(TENANT, fixture.admin, List.of())).hasMessage("没有要导入的账号");
        var many = IntStream.rangeClosed(1, 501).mapToObj(i -> row(i + 1, "user" + i, "用户", "员工")).toList();
        assertThatThrownBy(() -> service.check(TENANT, fixture.admin, many)).hasMessageContaining("500");
    }
}
