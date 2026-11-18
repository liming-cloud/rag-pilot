package com.liming.rag.evaluation.application;

import java.time.OffsetDateTime;

/** 一个题集，可以包含多个知识库的题。 */
public record EvalSet(long id, String name, String description, OffsetDateTime updatedAt) {
}
