package com.liming.rag.ai;

import java.util.function.Supplier;

/**
 * 用量归属（#224）：一段工作里的模型调用不按各自的用途（回答、重排、向量化……）记，统一记到指定用途下，
 * 如知识质量的试问和运行记为 {@link com.liming.rag.ai.common.ModelCallUsage#EVALUATION}，“模型用量”里单独可见，不混进员工问答的用量。
 *
 * <p>归属跟着调用线程走：经 {@code applicationTaskExecutor} 派出的并行任务（检索、低分改写）会继承，流式生成在发起时取定。
 * 工作结束后恢复原来的归属，嵌套时以内层为准。
 */
public interface UsageAttribution {

    <T> T attribute(String purpose, Supplier<T> work);
}
