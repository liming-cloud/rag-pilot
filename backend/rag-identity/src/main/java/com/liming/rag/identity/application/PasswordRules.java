package com.liming.rag.identity.application;

/** 密码规则（D-038）：8–64 位，不强制复杂度，不能与用户名相同。 */
final class PasswordRules {

    private PasswordRules() {
    }

    static void check(String username, String password) {
        if (password == null || password.length() < 8 || password.length() > 64) {
            throw new IdentityException(IdentityException.Reason.INVALID, "密码必须是 8 到 64 个字符");
        }
        if (password.equalsIgnoreCase(username)) {
            throw new IdentityException(IdentityException.Reason.INVALID, "密码不能与用户名相同");
        }
    }
}
