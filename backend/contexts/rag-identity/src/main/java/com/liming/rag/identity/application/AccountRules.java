package com.liming.rag.identity.application;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.regex.Pattern;

/**
 * 账号的规则（D-038、#136）：用户名、显示名的格式，以及连续输错密码后的锁定。新建账号、批量导入、登录都按这里判断，
 * 不各写一份。identity 不建 domain 包（AGENTS.md），规则收在应用层这一处。
 */
final class AccountRules {

    static final Pattern USERNAME = Pattern.compile("[A-Za-z0-9_.-]{3,50}");
    static final int MAX_DISPLAY_NAME_LENGTH = 50;
    static final String USERNAME_PROBLEM = "用户名必须是 3 到 50 个字母、数字或 _ . -";
    static final String DISPLAY_NAME_PROBLEM = "显示名必须是 1 到 " + MAX_DISPLAY_NAME_LENGTH + " 个字符";

    /** 同一账号连续输错这么多次就锁定，按账号锁、不按 IP 锁（网点常共用网络）。 */
    static final int MAX_FAILED_ATTEMPTS = 5;
    static final Duration LOCK_DURATION = Duration.ofMinutes(15);

    private AccountRules() {
    }

    /** 用户名格式不对时的说明；没问题时返回 null。调用方先去掉首尾空格。 */
    static String usernameProblem(String username) {
        return username != null && USERNAME.matcher(username).matches() ? null : USERNAME_PROBLEM;
    }

    /** 显示名格式不对时的说明；没问题时返回 null。调用方先去掉首尾空格。 */
    static String displayNameProblem(String displayName) {
        return displayName == null || displayName.isEmpty() || displayName.length() > MAX_DISPLAY_NAME_LENGTH
                ? DISPLAY_NAME_PROBLEM : null;
    }

    /**
     * 又输错一次、连续错误累计到 {@code attempts} 次之后：到上限时锁到什么时候，没到时为 null。
     */
    static OffsetDateTime lockedUntil(int attempts, OffsetDateTime now) {
        return attempts >= MAX_FAILED_ATTEMPTS ? now.plus(LOCK_DURATION) : null;
    }
}
