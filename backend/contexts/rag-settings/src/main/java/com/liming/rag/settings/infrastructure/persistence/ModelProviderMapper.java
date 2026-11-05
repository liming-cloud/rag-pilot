package com.liming.rag.settings.infrastructure.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** model_provider 表，只有单表读写，用 MyBatis-Plus 的通用方法。 */
@Mapper
interface ModelProviderMapper extends BaseMapper<ModelProviderDO> {
}
