package com.liming.rag.data.infrastructure.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** data_scope_grant：全部走通用方法。 */
@Mapper
interface DataScopeGrantMapper extends BaseMapper<DataScopeGrantDO> {
}
