package com.liming.rag.evaluation.infrastructure.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** eval_run；每个题集最近一次运行在 XML 里。 */
@Mapper
interface EvalRunMapper extends BaseMapper<EvalRunDO> {

    List<EvalRunDO> latest(@Param("tenantId") long tenantId);
}
