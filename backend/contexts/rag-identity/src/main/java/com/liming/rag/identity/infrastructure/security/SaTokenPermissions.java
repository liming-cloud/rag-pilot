package com.liming.rag.identity.infrastructure.security;

import cn.dev33.satoken.stp.StpInterface;
import com.liming.rag.identity.application.role.AccessService;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Sa-Token 的权限来源：{@code @SaCheckPermission} 校验时取账号现在的权限点，每次都读库（D-041），判定规则在
 * {@link AccessService}。不用角色名做校验，接口只认权限点。
 */
@Component
class SaTokenPermissions implements StpInterface {

    private final AccessService access;

    SaTokenPermissions(AccessService access) {
        this.access = access;
    }

    @Override
    public List<String> getPermissionList(Object loginId, String loginType) {
        return access.permissionsOf(Long.parseLong(loginId.toString()));
    }

    @Override
    public List<String> getRoleList(Object loginId, String loginType) {
        return List.of();
    }
}
