package com.liming.rag.answering.infrastructure.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** answer_citation_open 表的单表写入。 */
@Mapper
interface CitationOpenMapper extends BaseMapper<CitationOpenDO> {
}
