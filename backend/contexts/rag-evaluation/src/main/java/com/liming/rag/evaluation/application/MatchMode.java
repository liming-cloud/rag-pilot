package com.liming.rag.evaluation.application;

/** 期望多个文档时：全部命中才算，或命中任一份就算。 */
public enum MatchMode {
    ALL,
    ANY
}
