package com.liming.rag.settings.application.model;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/** 模型用量与单价的存储端口（#169）。 */
public interface ModelUsageStore {

    /** 把一次调用累加到当天、这个用途、服务商和模型的行上；并发调用不丢数。 */
    void add(LocalDate day, String purpose, String provider, String model, long inputTokens, long outputTokens);

    /** 两端都包含。 */
    List<UsageRow> usage(LocalDate from, LocalDate to);

    /** 用量里出现过的模型名。 */
    List<String> usedModels();

    List<ModelPrice> prices();

    void savePrice(ModelPrice price, OffsetDateTime at);

    /** 删掉早于 {@code day} 的用量，返回删除的行数。 */
    int deleteBefore(LocalDate day);
}
