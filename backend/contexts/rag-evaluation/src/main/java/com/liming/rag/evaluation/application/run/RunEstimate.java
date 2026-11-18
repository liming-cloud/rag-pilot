package com.liming.rag.evaluation.application.run;

import java.math.BigDecimal;
import org.jspecify.annotations.Nullable;

/**
 * 运行前的预估（D-069：先不设上限，只提示）。
 *
 * @param questions 参与运行的题数（运行人能看到、没过期的题）
 * @param skippedStale 跳过的过期题数
 * @param cost 预估费用（元）：每题按近 30 天每次回答的平均费用估；没有单价或近期用量时为 null
 */
public record RunEstimate(int questions, int skippedStale, @Nullable BigDecimal cost, String currency) {
}
