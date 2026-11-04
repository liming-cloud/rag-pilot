package com.liming.rag.answering.application.insight;

/** SEMANTIC：按向量相似度归类；TEXT：嵌入模型不可用，退回按原文分组。 */
public enum GapGrouping {
    SEMANTIC, TEXT
}
