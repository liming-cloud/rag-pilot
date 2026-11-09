package com.liming.rag.settings.application.model;

import java.math.BigDecimal;
import org.jspecify.annotations.Nullable;

/** 管理端的单价一行：有用量或填过单价的模型；没填过单价时两个价格为 null。 */
public record PriceView(String model, @Nullable BigDecimal inputPerMillion, @Nullable BigDecimal outputPerMillion) {
}
