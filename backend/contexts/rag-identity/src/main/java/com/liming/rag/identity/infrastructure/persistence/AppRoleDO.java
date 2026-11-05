package com.liming.rag.identity.infrastructure.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import org.jspecify.annotations.NullUnmarked;

/** app_role 的一行，单表读写用。带权限点的角色查询结果见 {@link RoleDO}。 */
@TableName("app_role")
@NullUnmarked
final class AppRoleDO {

    @TableId(type = IdType.AUTO)
    Long id;
    Long tenantId;
    String name;
    String builtin;

    Long getTenantId() {
        return tenantId;
    }

    String getName() {
        return name;
    }
}
