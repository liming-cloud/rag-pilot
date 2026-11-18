package com.liming.rag.evaluation.infrastructure.persistence;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** eval_result：有数组列，读写都在 XML 里。 */
@Mapper
interface EvalResultMapper {

    int insertAll(@Param("rows") List<EvalResultDO> rows);

    int saveOutcome(@Param("row") EvalResultDO row);

    List<EvalResultDO> findByRun(@Param("runId") long runId);
}
