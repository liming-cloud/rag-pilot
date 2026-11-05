package com.liming.rag.identity.infrastructure.persistence;

import com.baomidou.mybatisplus.annotation.TableName;
import org.jspecify.annotations.NullUnmarked;

/** app_role_permission 的一行（主键是两列，没有 id）。 */
@TableName("app_role_permission")
@NullUnmarked
final class AppRolePermissionDO {

    Long roleId;
    String permission;

    Long getRoleId() {
        return roleId;
    }
}
