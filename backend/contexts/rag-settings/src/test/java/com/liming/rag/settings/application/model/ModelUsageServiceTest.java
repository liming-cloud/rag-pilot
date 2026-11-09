package com.liming.rag.settings.application.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.liming.rag.identity.api.AuditTrail;
import com.liming.rag.settings.application.SettingsException;
import com.liming.rag.shared.TenantId;
import com.liming.rag.shared.UserId;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 模型用量与费用（#169）：按日累加、分组、按单价估算费用、单价校验与审计、过期清理。 */
class ModelUsageServiceTest {

    private static final TenantId TENANT = new TenantId(1);
    private static final UserId ADMIN = new UserId(7);

    private final MemoryStore store = new MemoryStore();
    private final List<String> audits = new ArrayList<>();
    // 2026-11-09 23:30 UTC 在上海已经是 11-10：一天按统计时区划分。
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-09T23:30:00Z"), ZoneOffset.UTC);
    private final ModelUsageService service = new ModelUsageService(store, () -> ZoneId.of("Asia/Shanghai"),
            (AuditTrail) (tenant, actor, action, type, id, detail) -> audits.add(action + " " + id + " " + detail),
            clock);

    @Test
    @DisplayName("同一天、用途、服务商、模型的调用累加；一天按统计时区划分；服务商只存域名，不带路径和密钥")
    void accumulatesPerDayUnderStatsZone() {
        service.record("ANSWER", "https://api.example.com/v1", "m1", 100, 20);
        service.record("ANSWER", "https://api.example.com:8443/v1?key=secret", "m1", 50, 5);
        service.record("ANSWER", "https://api.example.com/v1", "m1", 10, 1);

        assertThat(store.rows).containsOnlyKeys("2026-11-10 ANSWER api.example.com m1",
                "2026-11-10 ANSWER api.example.com:8443 m1");
        assertThat(store.rows.get("2026-11-10 ANSWER api.example.com m1")).containsExactly(2L, 110L, 21L);
        assertThat(store.rows.toString()).doesNotContain("secret");
    }

    @Test
    @DisplayName("按单价估算费用：每百万 token 的输入、输出单价；没填单价的模型不计费用并标出；合计包含全部用量")
    void estimatesCostFromPrices() {
        store.rows.put("2026-11-09 ANSWER api.example.com m1", new long[] {3, 2_000_000, 500_000});
        store.rows.put("2026-11-09 RERANK api.example.com free", new long[] {1, 100, 0});
        service.savePrice(TENANT, ADMIN, "m1", new BigDecimal("2"), new BigDecimal("8"));

        var report = service.usage("2026-11-09", "2026-11-10", "purpose", 1, 20);

        assertThat(report.groupBy()).isEqualTo(UsageGroupBy.PURPOSE);
        assertThat(report.groups().items()).extracting(UsageGroup::key).containsExactly("ANSWER", "RERANK");
        assertThat(report.groups().items().getFirst().cost()).isEqualByComparingTo("8");
        assertThat(report.groups().items().getFirst().unpriced()).isFalse();
        assertThat(report.groups().items().getLast().cost()).isEqualByComparingTo("0");
        assertThat(report.groups().items().getLast().unpriced()).isTrue();
        assertThat(report.total().calls()).isEqualTo(4);
        assertThat(report.total().inputTokens()).isEqualTo(2_000_100);
        assertThat(report.total().cost()).isEqualByComparingTo("8");
    }

    @Test
    @DisplayName("按日、按月分组，最新的在前；默认看最近 30 天、按明细；范围外的用量不统计")
    void groupsByDayAndMonth() {
        store.rows.put("2026-10-31 ANSWER h m", new long[] {1, 10, 1});
        store.rows.put("2026-11-02 ANSWER h m", new long[] {2, 20, 2});
        store.rows.put("2026-11-09 ANSWER h m", new long[] {4, 40, 4});
        store.rows.put("2026-09-01 ANSWER h m", new long[] {8, 80, 8});

        assertThat(service.usage("2026-10-01", "2026-11-30", "MONTH", 1, 20).groups().items()).extracting(UsageGroup::key,
                UsageGroup::calls).containsExactly(org.assertj.core.groups.Tuple.tuple("2026-11", 6L),
                        org.assertj.core.groups.Tuple.tuple("2026-10", 1L));
        var defaults = service.usage(null, null, null, 1, 20);
        assertThat(defaults.groupBy()).isEqualTo(UsageGroupBy.DETAIL);
        assertThat(defaults.to()).isEqualTo(LocalDate.parse("2026-11-10"));
        assertThat(defaults.from()).isEqualTo(LocalDate.parse("2026-10-12"));
        assertThat(defaults.groups().items()).extracting(UsageGroup::key, UsageGroup::model)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("2026-11-09", "m"),
                        org.assertj.core.groups.Tuple.tuple("2026-11-02", "m"),
                        org.assertj.core.groups.Tuple.tuple("2026-10-31", "m"));
    }

    @Test
    @DisplayName("明细分页：每页条数与页码生效，总条数不受分页影响，合计包含全部页；明细行带用途、服务商和模型")
    void pagesDetailRows() {
        for (int day = 1; day <= 5; day++) {
            store.rows.put("2026-11-0" + day + " ANSWER api.example.com m", new long[] {1, 10, 1});
        }

        var second = service.usage("2026-11-01", "2026-11-09", "DETAIL", 2, 2);

        assertThat(second.groups().total()).isEqualTo(5);
        assertThat(second.groups().page()).isEqualTo(2);
        assertThat(second.groups().items()).extracting(UsageGroup::key).containsExactly("2026-11-03", "2026-11-02");
        assertThat(second.groups().items().getFirst()).extracting(UsageGroup::purpose, UsageGroup::provider,
                UsageGroup::model).containsExactly("ANSWER", "api.example.com", "m");
        assertThat(second.total().calls()).isEqualTo(5);
        assertThat(service.usage("2026-11-01", "2026-11-09", "DETAIL", 9, 2).groups().items()).isEmpty();
    }

    @Test
    @DisplayName("日期格式、范围、分组不合规时拒绝，说明原因")
    void rejectsBadQueries() {
        assertThatThrownBy(() -> service.usage("11/01", null, null, 1, 20)).isInstanceOf(SettingsException.class)
                .hasMessageContaining("yyyy-MM-dd");
        assertThatThrownBy(() -> service.usage("2026-11-09", "2026-11-01", null, 1, 20))
                .hasMessageContaining("不能晚于");
        assertThatThrownBy(() -> service.usage("2025-01-01", "2026-11-01", null, 1, 20)).hasMessageContaining("一年");
        assertThatThrownBy(() -> service.usage(null, null, "USER", 1, 20)).hasMessageContaining("分组");
    }

    @Test
    @DisplayName("改单价记一条审计、说明里有模型和价格；负数、缺项、超长模型名拒绝，不写库")
    void pricesAreValidatedAndAudited() {
        var saved = service.savePrice(TENANT, ADMIN, " m1 ", new BigDecimal("1.5"), new BigDecimal("6"));

        assertThat(saved.model()).isEqualTo("m1");
        assertThat(audits).singleElement().asString().startsWith("MODEL_PRICE_CHANGED m1 ")
                .contains("1.5000", "6.0000");
        assertThatThrownBy(() -> service.savePrice(TENANT, ADMIN, "m1", new BigDecimal("-1"), BigDecimal.ONE))
                .isInstanceOf(SettingsException.class).hasMessageContaining("输入单价");
        assertThatThrownBy(() -> service.savePrice(TENANT, ADMIN, "m1", BigDecimal.ONE, null))
                .hasMessageContaining("输出单价");
        assertThatThrownBy(() -> service.savePrice(TENANT, ADMIN, "x".repeat(201), BigDecimal.ONE, BigDecimal.ONE))
                .hasMessageContaining("模型名称");
        assertThat(store.prices).hasSize(1);
        assertThat(audits).hasSize(1);
    }

    @Test
    @DisplayName("单价列表包含有用量或填过价的模型，没填过价的价格为空")
    void listsModelsWithAndWithoutPrice() {
        store.rows.put("2026-11-09 ANSWER h used", new long[] {1, 1, 1});
        service.savePrice(TENANT, ADMIN, "priced-only", BigDecimal.ONE, BigDecimal.TEN);

        assertThat(service.prices()).extracting(PriceView::model, PriceView::inputPerMillion)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("priced-only", new BigDecimal("1.0000")),
                        org.assertj.core.groups.Tuple.tuple("used", null));
    }

    @Test
    @DisplayName("清理只删超过 365 天的用量")
    void purgesExpiredUsage() {
        store.rows.put("2025-11-09 ANSWER h old", new long[] {1, 1, 1});
        store.rows.put("2025-11-11 ANSWER h edge", new long[] {1, 1, 1});

        assertThat(service.purge()).isEqualTo(1);
        assertThat(store.rows).containsOnlyKeys("2025-11-11 ANSWER h edge");
    }

    /** 键是“日期 用途 服务商 模型”，值是 [调用数, 输入, 输出]。 */
    private static final class MemoryStore implements ModelUsageStore {
        final Map<String, long[]> rows = new TreeMap<>();
        final Map<String, ModelPrice> prices = new TreeMap<>();

        @Override
        public void add(LocalDate day, String purpose, String provider, String model, long input, long output) {
            var total = rows.computeIfAbsent(day + " " + purpose + " " + provider + " " + model, k -> new long[3]);
            total[0]++;
            total[1] += input;
            total[2] += output;
        }

        @Override
        public List<UsageRow> usage(LocalDate from, LocalDate to) {
            return rows.entrySet().stream().map(e -> {
                var key = e.getKey().split(" ");
                return new UsageRow(LocalDate.parse(key[0]), key[1], key[2], key[3], e.getValue()[0],
                        e.getValue()[1], e.getValue()[2]);
            }).filter(row -> !row.day().isBefore(from) && !row.day().isAfter(to)).toList();
        }

        @Override
        public List<String> usedModels() {
            return rows.keySet().stream().map(key -> key.split(" ")[3]).distinct().sorted().toList();
        }

        @Override
        public List<ModelPrice> prices() {
            return List.copyOf(prices.values());
        }

        @Override
        public void savePrice(ModelPrice price, OffsetDateTime at) {
            prices.put(price.model(), price);
        }

        @Override
        public int deleteBefore(LocalDate day) {
            int before = rows.size();
            rows.keySet().removeIf(key -> LocalDate.parse(key.split(" ")[0]).isBefore(day));
            return before - rows.size();
        }
    }
}
