package com.liming.rag.evaluation.application.suite;

import java.util.List;
import org.jspecify.annotations.Nullable;

/** 新建、修改标准题的输入，原样来自请求，由 {@link SuiteService} 校验和解析。 */
public record QuestionInput(@Nullable String question, long knowledgeBaseId, @Nullable String expectation,
        @Nullable String match, @Nullable List<Long> expectedDocumentIds) {
}
