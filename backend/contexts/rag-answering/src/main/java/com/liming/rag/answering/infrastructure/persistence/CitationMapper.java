package com.liming.rag.answering.infrastructure.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** answer_citation 表的单表写入；按回答查出处要连表核对租户，在 {@link AnswerRecordMapper} 里。 */
@Mapper
interface CitationMapper extends BaseMapper<CitationDO> {
}
