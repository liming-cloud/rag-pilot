package com.liming.rag.ai;

import com.liming.rag.ai.common.ModelCallUsage;

/**
 * 每次模型调用的用量去向，由系统设置模块（rag-settings）实现（#169）：问答、改写、重排、入库向量化每调用一次就报一次，
 * 只含用途、服务商地址、模型和 token 数，不含提示词、回答和提问的人。
 *
 * <p>这是由上下文实现的接口（SPI），不是 rag-ai 提供的能力：实现注册成 Spring Bean 即可，rag-ai 会取用；没有实现时不记录。
 * 记录失败只写日志，不影响这次调用。
 */
public interface UsageRecording {

    void record(ModelCallUsage usage);
}
