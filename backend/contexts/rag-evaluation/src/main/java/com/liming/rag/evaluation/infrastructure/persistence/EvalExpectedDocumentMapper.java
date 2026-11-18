package com.liming.rag.evaluation.infrastructure.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** eval_expected_document；接到新版本的 UPDATE 在 XML 里。 */
@Mapper
interface EvalExpectedDocumentMapper extends BaseMapper<EvalExpectedDocumentDO> {

    /** 把期望 {@code replacedDocumentId} 的题接到 {@code documentId}；已同时期望新版本的题不改（由调用方删掉旧的一行）。 */
    int follow(@Param("tenantId") long tenantId, @Param("replacedDocumentId") long replacedDocumentId,
            @Param("documentId") long documentId);
}
