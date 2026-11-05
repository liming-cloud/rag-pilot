package com.liming.rag.settings.infrastructure.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** model_change 表，只有单表读写，分页用分页插件。 */
@Mapper
interface ModelChangeMapper extends BaseMapper<ModelChangeDO> {
}
