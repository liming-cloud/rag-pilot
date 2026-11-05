package com.liming.rag.identity.infrastructure.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.Collection;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * app_role 表：单表读写用通用方法；带权限点、按账号查角色、统计人数要连表，SQL 写在 {@code mapper/identity/AppRoleMapper.xml}。
 * 角色查询返回每个角色一行、权限点用 \u001F 连接，由调用方拆开。
 */
@Mapper
interface AppRoleMapper extends BaseMapper<AppRoleDO> {

    List<RoleDO> list(@Param("tenantId") long tenantId, @Param("id") Long id,
            @Param("builtin") String builtin);

    List<RoleDO> rolesOfUsers(@Param("userIds") Collection<Long> userIds);

    List<RoleUserCountDO> userCounts(@Param("tenantId") long tenantId);
}
