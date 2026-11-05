package com.liming.rag.identity.infrastructure.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.Collection;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** app_role_permission 表：删除用通用方法；一次插入多行写在 {@code mapper/identity/AppRolePermissionMapper.xml}。 */
@Mapper
interface AppRolePermissionMapper extends BaseMapper<AppRolePermissionDO> {

    void insertAll(@Param("roleId") long roleId, @Param("permissions") Collection<String> permissions);
}
