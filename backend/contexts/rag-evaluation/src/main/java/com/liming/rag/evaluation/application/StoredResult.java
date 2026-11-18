package com.liming.rag.evaluation.application;

import org.jspecify.annotations.Nullable;

/** 运行里的一道题：开始时的题目快照，和跑完后的结果（还没跑到时为 null）。 */
public record StoredResult(GoldenQuestion question, @Nullable QuestionOutcome outcome) {
}
