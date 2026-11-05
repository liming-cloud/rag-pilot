package com.liming.rag.identity.infrastructure.persistence;

import com.baomidou.mybatisplus.annotation.TableName;
import org.jspecify.annotations.NullUnmarked;

/** app_user_role 的一行（主键是两列，没有 id）。 */
@TableName("app_user_role")
@NullUnmarked
final class AppUserRoleDO {

    Long userId;
    Long roleId;

    Long getUserId() {
        return userId;
    }

    Long getRoleId() {
        return roleId;
    }
}
