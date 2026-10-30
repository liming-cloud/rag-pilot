package com.liming.rag.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
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
 * 登录失败与锁定。登录成功和会话由 Sa-Token 管理，在 rag-app 的 AuthTests 里验证；这里只测不依赖会话的规则。
 */
class AuthServiceTest {

    private final IdentityFixture fixture = new IdentityFixture();
    private final MutableClock clock = new MutableClock();
    private final AuthService auth = new AuthService(fixture.store, fixture.store, fixture.access, clock);

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
