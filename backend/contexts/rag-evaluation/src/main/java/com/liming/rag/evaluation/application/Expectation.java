package com.liming.rag.evaluation.application;

/** 标准题的期望：命中期望文档，或者应当拒答（资料里没有答案、超出范围的问题）。 */
public enum Expectation {
    DOCUMENTS,
    REFUSAL
}
