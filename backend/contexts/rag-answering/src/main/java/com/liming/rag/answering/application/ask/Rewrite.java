package com.liming.rag.answering.application.ask;

import org.jspecify.annotations.Nullable;

/**
 * 低分时改写问题的情况，用于评测和排查。
 *
 * @param question 改写后的问题，只有 {@link Outcome#REWRITTEN} 时有值
 * @param outputChars 模型改写输出的字数（截取前），没有调用模型时为 0
 * @param modelMillis 模型改写的耗时，没有调用模型时为 null
 * @param retrievalMillis 用改写后的问题重新检索的耗时，没有重新检索时为 null
 * @param rerankMillis 重新重排的耗时，没有重新重排时为 null
 */
public record Rewrite(Outcome outcome, String question, int outputChars, @Nullable Long modelMillis, @Nullable Long retrievalMillis,
        @Nullable Long rerankMillis) {

    public enum Outcome {
        /** 改写前最高分低于下限，判定改写也救不回，直接拒答。 */
        SKIPPED_BELOW_FLOOR,
        /** 模型改写超时，放弃改写。 */
        TIMED_OUT,
        /** 模型没给出有效改写（空、NO_ANSWER、太长或和原问题一样）。 */
        NO_CHANGE,
        /** 已改写并用新问题重新检索、重排。 */
        REWRITTEN
    }
}
