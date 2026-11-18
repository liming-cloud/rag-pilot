package com.liming.rag.settings.api;

import java.math.BigDecimal;
import java.util.Optional;

/** 按单价表（D-065）和近期用量估算的费用，供运行前提示用（#224）；只读，不分租户。 */
public interface ModelCosts {

    /**
     * 近 30 天每次回答的平均费用（元）：回答、备用回答、重排的费用 ÷ 回答的调用次数。
     * 没有回答，或其中有没填单价的模型时为空（估不准就不估）。
     */
    Optional<BigDecimal> averageAnswerCost();
}
