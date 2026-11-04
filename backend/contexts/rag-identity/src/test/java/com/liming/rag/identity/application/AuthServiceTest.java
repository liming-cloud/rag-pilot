package com.liming.rag.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.liming.rag.shared.ErrorKind;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 登录、退出、改自己的密码，以及登录失败与锁定。会话用内存实现；Sa-Token 的会话和令牌在 rag-app 的 AuthTests 里验证。
 */
class AuthServiceTest {

    private final IdentityFixture fixture = new IdentityFixture();
    private final MutableClock clock = new MutableClock();
    private final AuthService auth = new AuthService(fixture.store, fixture.store, fixture.access,
            fixture.sessions, fixture.passwords, clock);

    @Test
    @DisplayName("用户名不存在、密码错误、账号停用都给同一个提示")
    void badCredentialsLookTheSame() {
        var leaver = fixture.account("leaver01", fixture.staffRole);
        fixture.store.setEnabled(leaver.value(), false);
        fixture.account("staff01", fixture.staffRole);

        var errors = List.of(catchLogin("nobody", IdentityFixture.PASSWORD), catchLogin("staff01", "wrong-password"),
                catchLogin("leaver01", IdentityFixture.PASSWORD));

        assertThat(errors).allSatisfy(error -> {
            assertThat(error).isInstanceOf(IdentityException.class).hasMessage("用户名或密码错误");
            assertThat(((IdentityException) error).kind()).isEqualTo(ErrorKind.UNAUTHENTICATED);
        });
    }

    @Test
    @DisplayName("连续输错 5 次锁 15 分钟，期间密码对了也不能登录；到期自动解锁")
    void locksAfterFiveFailures() {
        var staff = fixture.account("staff01", fixture.staffRole);
        for (int i = 1; i <= 4; i++) {
            assertThat(catchLogin("staff01", "wrong-password")).hasMessage("用户名或密码错误");
        }
        assertThat(fixture.get(staff).failedAttempts()).isEqualTo(4);

        var fifth = catchLogin("staff01", "wrong-password");
        assertThat(fifth).isInstanceOf(IdentityException.class).hasMessageContaining("已锁定").hasMessageContaining("15 分钟");
        assertThat(((IdentityException) fifth).code()).isEqualTo("IDENTITY_LOCKED");

        clock.advance(Duration.ofMinutes(10));
        assertThat(catchLogin("staff01", IdentityFixture.PASSWORD)).hasMessageContaining("请 5 分钟后再试");

        clock.advance(Duration.ofMinutes(6));
        assertThat(catchLogin("staff01", "wrong-password")).hasMessage("用户名或密码错误");
        assertThat(fixture.get(staff).failedAttempts()).isEqualTo(1);
    }

    @Test
    @DisplayName("登录成功：经会话端口登录并记下租户，清掉之前的失败计数，返回权限点")
    void signsInAndClearsFailures() {
        var staff = fixture.account("staff01", fixture.staffRole);
        catchLogin("staff01", "wrong-password");

        var signIn = auth.login(" staff01 ", IdentityFixture.PASSWORD);

        assertThat(signIn.token()).isEqualTo("token-" + staff.value());
        assertThat(fixture.sessions.currentTenant).isEqualTo(IdentityFixture.TENANT);
        assertThat(fixture.get(staff).failedAttempts()).isZero();
        assertThat(signIn.user().permissions()).containsExactly(TestCatalog.CHAT_USE);
        assertThat(auth.me().account().id()).isEqualTo(staff.value());
        auth.logout();
        assertThat(fixture.sessions.currentLogouts).isEqualTo(1);
    }

    @Test
    @DisplayName("改自己的密码：旧密码不对时拒绝；改完不再要求先改密码，新密码按规则校验")
    void changesOwnPassword() {
        var staff = fixture.account("staff01", fixture.staffRole);
        fixture.store.setPassword(staff.value(), fixture.passwords.hash(IdentityFixture.PASSWORD), true);
        auth.login("staff01", IdentityFixture.PASSWORD);

        assertThatThrownBy(() -> auth.changePassword("wrong-password", "brand-new-password"))
                .isInstanceOf(IdentityException.class).hasMessage("当前密码不对");
        assertThatThrownBy(() -> auth.changePassword(IdentityFixture.PASSWORD, "staff01"))
                .isInstanceOf(IdentityException.class);
        auth.changePassword(IdentityFixture.PASSWORD, "brand-new-password");

        assertThat(fixture.get(staff).mustChangePassword()).isFalse();
        assertThat(fixture.passwords.matches("brand-new-password", fixture.get(staff).passwordHash())).isTrue();
    }

    private Throwable catchLogin(String username, String password) {
        return catchThrowable(() -> auth.login(username, password));
    }

    private static final class MutableClock extends Clock {

        private Instant now = Instant.parse("2026-10-30T02:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
