package com.liming.rag.identity.infrastructure.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.time.OffsetDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** app_user 表。分页筛选和登录失败计数写在 {@code mapper/identity/AppUserMapper.xml}。 */
@Mapper
public interface AppUserMapper extends BaseMapper<AppUserDO> {

    /** status 为 ENABLED、DISABLED、LOCKED 或 null；keyword 匹配用户名或显示名（不区分大小写）。 */
    List<AppUserDO> page(@Param("tenantId") long tenantId, @Param("keyword") String keyword,
            @Param("roleId") Long roleId, @Param("status") String status, @Param("now") OffsetDateTime now,
            @Param("offset") long offset, @Param("size") int size);

    long countPage(@Param("tenantId") long tenantId, @Param("keyword") String keyword,
            @Param("roleId") Long roleId, @Param("status") String status, @Param("now") OffsetDateTime now);

    /** 失败次数原子加一，返回加一后的次数。 */
    int incrementFailedLogins(@Param("id") long id);
}
