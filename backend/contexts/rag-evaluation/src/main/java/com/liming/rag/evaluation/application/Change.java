package com.liming.rag.evaluation.application;

/** 一道题和同一题集上一次运行比：新失败、新通过、不变；上一次没有这道题（或还没跑到）时为 NEW。 */
public enum Change {
    NEWLY_FAILED,
    NEWLY_PASSED,
    SAME,
    NEW
}
