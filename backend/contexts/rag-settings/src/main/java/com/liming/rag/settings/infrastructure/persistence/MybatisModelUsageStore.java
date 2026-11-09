package com.liming.rag.settings.infrastructure.persistence;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.liming.rag.settings.application.model.ModelPrice;
import com.liming.rag.settings.application.model.ModelUsageStore;
import com.liming.rag.settings.application.model.UsageRow;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.stereotype.Repository;

/** 范围查询、单价读取和过期删除用 MyBatis-Plus 的通用方法；累加和单价保存是 upsert，写在 XML 里。 */
@Repository
class MybatisModelUsageStore implements ModelUsageStore {

    private final ModelUsageDailyMapper usage;
    private final ModelPriceMapper prices;

    MybatisModelUsageStore(ModelUsageDailyMapper usage, ModelPriceMapper prices) {
        this.usage = usage;
        this.prices = prices;
    }

    @Override
    public void add(LocalDate day, String purpose, String provider, String model, long inputTokens,
            long outputTokens) {
        usage.add(day, purpose, provider, model, inputTokens, outputTokens);
    }

    @Override
    public List<UsageRow> usage(LocalDate from, LocalDate to) {
        return usage.selectList(new LambdaQueryWrapper<ModelUsageDailyDO>()
                        .between(ModelUsageDailyDO::getDay, from, to))
                .stream()
                .map(row -> new UsageRow(row.day, row.purpose, row.provider, row.model, row.calls, row.inputTokens,
                        row.outputTokens))
                .toList();
    }

    @Override
    public List<String> usedModels() {
        return usage.usedModels();
    }

    @Override
    public List<ModelPrice> prices() {
        return prices.selectList(null).stream()
                .map(row -> new ModelPrice(row.model, row.inputPerMillion, row.outputPerMillion))
                .toList();
    }

    @Override
    public void savePrice(ModelPrice price, OffsetDateTime at) {
        prices.save(price.model(), price.inputPerMillion(), price.outputPerMillion(), at);
    }

    @Override
    public int deleteBefore(LocalDate day) {
        return usage.delete(new LambdaQueryWrapper<ModelUsageDailyDO>().lt(ModelUsageDailyDO::getDay, day));
    }
}
