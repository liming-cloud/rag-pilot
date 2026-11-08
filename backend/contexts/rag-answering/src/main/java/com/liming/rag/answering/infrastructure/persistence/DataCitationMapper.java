package com.liming.rag.answering.infrastructure.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** answer_data_citation 表的单表读写；只给已按租户查出的回答补数据出处。 */
@Mapper
interface DataCitationMapper extends BaseMapper<DataCitationDO> {
}
