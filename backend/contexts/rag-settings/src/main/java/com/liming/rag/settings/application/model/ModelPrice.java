package com.liming.rag.settings.application.model;

import java.math.BigDecimal;

/** 一个模型每百万 token 的输入、输出单价（元）。 */
public record ModelPrice(String model, BigDecimal inputPerMillion, BigDecimal outputPerMillion) {
}
