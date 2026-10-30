package com.liming.rag.identity.application;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 账号配置（{@code rag.identity}）。
 *
 * @param admin 账号表为空时用来创建超级管理员；已有账号时不生效
 * @param branding 登录页、顶栏显示的产品名称和提示（D-042）；M5 做系统设置页后改由管理员在界面里修改，这里作默认值
 */
@ConfigurationProperties("rag.identity")
public record IdentityProperties(@DefaultValue Admin admin, @DefaultValue Branding branding) {

    /**
     * 留空（部署文件里变量为空）时用内核默认值。
     *
     * @param name 产品名称，最多 30 个字符
     * @param tagline 登录页名称下方的一句话，最多 60 个字符
     * @param loginHint 登录按钮下方的提示，最多 100 个字符
     */
    public record Branding(@DefaultValue("") String name, @DefaultValue("") String tagline,
            @DefaultValue("") String loginHint) {

        /** 内核默认值，与前端 lib/branding.ts 的兜底一致。 */
        public static final String DEFAULT_NAME = "明答";
        public static final String DEFAULT_TAGLINE = "每个回答，都有出处";
        public static final String DEFAULT_LOGIN_HINT = "账号由管理员开通；忘记密码请联系管理员。";

        public Branding {
            name = orDefault(name, DEFAULT_NAME, 30, "name");
            tagline = orDefault(tagline, DEFAULT_TAGLINE, 60, "tagline");
            loginHint = orDefault(loginHint, DEFAULT_LOGIN_HINT, 100, "login-hint");
        }

        private static String orDefault(String value, String fallback, int max, String key) {
            var trimmed = value == null ? "" : value.strip();
            if (trimmed.length() > max) {
                throw new IllegalArgumentException("rag.identity.branding." + key + " 最多 " + max + " 个字符");
            }
            return trimmed.isEmpty() ? fallback : trimmed;
        }
    }

    /**
     * @param tenantId 初始管理员所在的租户；之后的账号跟随创建它的管理员，请求的租户从登录会话取
     * @param username 为空时不创建，只提示
     * @param password 不进日志
     * @param resetPassword 不为空时，启动时把超级管理员的密码重置为它；用完要删掉（D-038）。不进日志
     */
    public record Admin(@DefaultValue("1") long tenantId, @DefaultValue("") String username,
            @DefaultValue("") String password, @DefaultValue("") String resetPassword) {

        public Admin {
            if (tenantId <= 0) {
                throw new IllegalArgumentException("rag.identity.admin.tenant-id 必须为正数");
            }
        }

        /** 不输出密码。 */
        @Override
        public String toString() {
            return "Admin[tenantId=" + tenantId + ", username=" + username + "]";
        }
    }
}
