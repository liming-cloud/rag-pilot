package com.liming.rag.identity.infrastructure.persistence;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * app_role、app_role_permission、app_user_role 三张表，SQL 写在 {@code mapper/identity/AppRoleMapper.xml}。
 * 角色查询返回每个角色一行、权限点用 \u001F 连接，由调用方拆开。
 */
@Mapper
interface AppRoleMapper {

    List<Map<String, Object>> list(@Param("tenantId") long tenantId, @Param("id") Long id,
            @Param("builtin") String builtin);

    List<Map<String, Object>> rolesOfUsers(@Param("userIds") Collection<Long> userIds);

    long insert(@Param("tenantId") long tenantId, @Param("name") String name, @Param("builtin") String builtin);

    void rename(@Param("id") long id, @Param("name") String name);

    void delete(@Param("id") long id);

    void deletePermissions(@Param("roleId") long roleId);

    void insertPermissions(@Param("roleId") long roleId, @Param("permissions") Collection<String> permissions);

    boolean existsByName(@Param("tenantId") long tenantId, @Param("name") String name);

    List<Map<String, Object>> userCounts(@Param("tenantId") long tenantId);

    List<Long> userIds(@Param("roleId") long roleId);

    void deleteUserRoles(@Param("userId") long userId);

    void insertUserRoles(@Param("userId") long userId, @Param("roleIds") Collection<Long> roleIds);
}
