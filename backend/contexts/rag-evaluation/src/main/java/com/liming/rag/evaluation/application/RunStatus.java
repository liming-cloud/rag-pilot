package com.liming.rag.evaluation.application;

/** 运行状态：后台逐题运行中、跑完、中途失败（模型不可用、应用重启，已跑完的题照常保留）。 */
public enum RunStatus {
    RUNNING,
    DONE,
    FAILED
}
