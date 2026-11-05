package com.liming.rag.settings.api;

import java.time.ZoneId;

/**
 * 部署的统计时区（{@code rag.settings.stats-zone}，D-045）：使用统计的“一天”、资料有效期的“今天”都按它划分。
 * 部署配置，不属于行业包；问答、知识库等上下文需要“今天”时都经它取，不各自读配置（#136）。
 */
@FunctionalInterface
public interface StatsZone {

    ZoneId statsZone();
}
