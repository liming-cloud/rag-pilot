package com.liming.rag.knowledge.infrastructure.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ChunkMapper extends BaseMapper<ChunkDO> {
}
