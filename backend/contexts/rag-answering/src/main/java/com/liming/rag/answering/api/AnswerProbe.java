package com.liming.rag.answering.api;

import com.liming.rag.shared.TenantId;
import com.liming.rag.shared.UserId;

/**
 * 只读的回答入口（#224、D-070）：知识质量的试问和标准题运行用。走和员工问答相同的检索、重排、生成和引用校验，
 * 按提问人自己的权限检索（检索过滤仍只在 knowledge 一处，A-05）；不写回答记录、不进会话、不计入频率限制、
 * 知识缺口和使用统计。
 */
public interface AnswerProbe {

    /**
     * 在一个知识库里回答；提问人无权访问这个库时检索不到任何资料，按拒答返回。
     *
     * @throws RuntimeException 问题为空或太长、模型服务不可用时抛出本模块的业务异常
     */
    ProbeAnswer ask(TenantId tenantId, UserId userId, long knowledgeBaseId, String question);
}
