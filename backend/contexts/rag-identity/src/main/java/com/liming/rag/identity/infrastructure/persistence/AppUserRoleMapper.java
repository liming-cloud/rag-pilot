package com.liming.rag.identity.infrastructure.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.Collection;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** app_user_role 表：查询和删除用通用方法；一次插入多行写在 {@code mapper/identity/AppUserRoleMapper.xml}。 */
@Mapper
interface AppUserRoleMapper extends BaseMapper<AppUserRoleDO> {

    void insertAll(@Param("userId") long userId, @Param("roleIds") Collection<Long> roleIds);
}
