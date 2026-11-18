package com.liming.rag.evaluation.application.suite;

import java.util.List;

/** 导入结果：导入的题数，和跳过的行及原因。 */
public record ImportOutcome(int imported, List<Skipped> skipped) {

    public record Skipped(int line, String reason) {
    }
}
