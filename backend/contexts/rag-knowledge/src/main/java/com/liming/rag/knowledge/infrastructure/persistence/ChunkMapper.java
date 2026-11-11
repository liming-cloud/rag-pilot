package com.liming.rag.knowledge.infrastructure.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 多行写入见 {@code mapper/knowledge/ChunkMapper.xml}。 */
@Mapper
public interface ChunkMapper extends BaseMapper<ChunkDO> {

    /** 一条 INSERT 写入多块，不回填 id。 */
    void insertAll(@Param("rows") List<ChunkDO> rows);
}
