package com.liming.rag.identity.application;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 账号配置（{@code rag.identity}）。
 *
 * @param admin 账号表为空时用来创建第一个管理员；已有账号时不生效
 */
@ConfigurationProperties("rag.identity")
public record IdentityProperties(@DefaultValue Admin admin) {

    /**
     * @param tenantId 初始管理员所在的租户；之后的账号跟随创建它的管理员，请求的租户从登录会话取
     * @param username 为空时不创建，只提示
     * @param password 不进日志
     */
    public record Admin(@DefaultValue("1") long tenantId, @DefaultValue("") String username,
            @DefaultValue("") String password) {

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
