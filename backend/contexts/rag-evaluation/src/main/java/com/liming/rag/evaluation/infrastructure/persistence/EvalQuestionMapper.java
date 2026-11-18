package com.liming.rag.evaluation.infrastructure.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** eval_question：全部走通用方法。 */
@Mapper
interface EvalQuestionMapper extends BaseMapper<EvalQuestionDO> {
}
