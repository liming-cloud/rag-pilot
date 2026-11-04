package com.liming.rag.answering.application.ask;

import org.jspecify.annotations.Nullable;

/**
 * 各阶段耗时（毫秒）。没走到的阶段为 null：检索不到时没有重排，拒答在生成前时没有生成。
 *
 * @param retrievalMillis 向量化问题 + 混合检索
 * @param rerankMillis 重排
 * @param firstTokenMillis 从开始生成到模型给出第一段文字
 * @param generationMillis 从开始生成到生成完成
 * @param rewriteMillis 低分时尝试改写问题并重新检索、重排的耗时（包括模型没给出有效改写的情况），没有尝试时为 null
 */
public record Timings(long retrievalMillis, @Nullable Long rerankMillis, @Nullable Long firstTokenMillis, @Nullable Long generationMillis,
        @Nullable Long rewriteMillis) {
}
