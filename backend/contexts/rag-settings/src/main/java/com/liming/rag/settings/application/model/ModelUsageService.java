package com.liming.rag.settings.application.model;

import com.liming.rag.identity.api.AuditTrail;
import com.liming.rag.settings.api.StatsZone;
import com.liming.rag.settings.application.SettingsException;
import com.liming.rag.shared.Enums;
import com.liming.rag.shared.Paged;
import com.liming.rag.shared.TenantId;
import com.liming.rag.shared.UserId;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 模型用量与费用（#169、D-056）：rag-ai 每调用一次模型就经 infrastructure 层报一次，这里按“日 × 用途 × 服务商 × 模型”累加；
 * 管理员按模型手填每百万 token 的单价，费用按当前单价估算（改价后历史也按新价算，所以是“大约”）。
 * 只存聚合数字，不存每次调用的明细，不记问题原文和提问的人；用量保留 {@value #RETENTION_DAYS} 天。
 * “一天”按部署的统计时区划分（{@link StatsZone}）。
 */
@Service
public class ModelUsageService {

    private static final Logger log = LoggerFactory.getLogger(ModelUsageService.class);

    static final int RETENTION_DAYS = 365;
    static final int DEFAULT_RANGE_DAYS = 30;
    private static final int MAX_NAME_LENGTH = 200;
    private static final BigDecimal MILLION = BigDecimal.valueOf(1_000_000);
    private static final BigDecimal MAX_PRICE = BigDecimal.valueOf(1_000_000);

    private final ModelUsageStore store;
    private final StatsZone zones;
    private final AuditTrail audit;
    private final Clock clock;

    ModelUsageService(ModelUsageStore store, StatsZone zones, AuditTrail audit, Clock clock) {
        this.store = store;
        this.zones = zones;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * 记一次调用。单独开事务：模型调用的前后可能已有只读事务，调用方回滚也不该丢掉已经花掉的 token。
     *
     * @param baseUrl 服务商地址，只存域名
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String purpose, String baseUrl, String model, long inputTokens, long outputTokens) {
        store.add(today(), cut(purpose, 20), provider(baseUrl), cut(model, MAX_NAME_LENGTH), inputTokens,
                outputTokens);
    }

    /** 每天清理超过保留期的用量。 */
    @Scheduled(cron = "0 15 3 * * *")
    @Transactional
    public int purge() {
        int deleted = store.deleteBefore(today().minusDays(RETENTION_DAYS));
        if (deleted > 0) {
            log.info("清理了 {} 行超过保留期的模型用量", deleted);
        }
        return deleted;
    }

    /**
     * 统计 {@code from} 到 {@code to}（含）的用量；不传时看最近 {@value #DEFAULT_RANGE_DAYS} 天，{@code groupBy} 默认按明细，分页返回。
     * 日期写 {@code yyyy-MM-dd}，最长一年。
     */
    @Transactional(readOnly = true)
    public UsageReport usage(@Nullable String from, @Nullable String to, @Nullable String groupBy, int page, int size) {
        var end = date(to, today());
        var start = date(from, end.minusDays(DEFAULT_RANGE_DAYS - 1));
        if (start.isAfter(end)) {
            throw invalid("开始日期不能晚于结束日期");
        }
        if (ChronoUnit.DAYS.between(start, end) >= RETENTION_DAYS + 1) {
            throw invalid("一次最多看一年");
        }
        var by = Enums.parseOrNull(UsageGroupBy.class, groupBy,
                () -> invalid("分组只能是 DETAIL、DAY、MONTH、PURPOSE 或 MODEL"));
        var grouping = by == null ? UsageGroupBy.DETAIL : by;
        var prices = new LinkedHashMap<String, ModelPrice>();
        store.prices().forEach(price -> prices.put(price.model(), price));
        var rows = store.usage(start, end);

        var groups = new LinkedHashMap<String, Tally>();
        var total = new Tally(null);
        for (var row : rows) {
            var tally = groups.computeIfAbsent(key(grouping, row), key -> new Tally(grouping == UsageGroupBy.DETAIL ? row : null));
            tally.add(row, prices.get(row.model()));
            total.add(row, prices.get(row.model()));
        }
        var result = new ArrayList<>(groups.entrySet().stream().map(e -> e.getValue().group(e.getKey())).toList());
        // 日期、月份最新的在前；用途、模型用得多的在前。
        Comparator<UsageGroup> byTokens = Comparator
                .comparingLong((UsageGroup g) -> g.inputTokens() + g.outputTokens()).reversed();
        result.sort(switch (grouping) {
            case DAY, MONTH -> Comparator.comparing(UsageGroup::key).reversed();
            case DETAIL -> Comparator.comparing(UsageGroup::key).reversed().thenComparing(byTokens);
            case PURPOSE, MODEL -> byTokens;
        });
        var paging = new Paged.Request(page, size);
        var from0 = (int) Math.min(paging.offset(), result.size());
        var pageItems = result.subList(from0, Math.min(result.size(), from0 + paging.size()));
        return new UsageReport(start, end, grouping,
                new Paged<>(List.copyOf(pageItems), result.size(), paging.page(), paging.size()), total.group("合计"));
    }

    /** 有用量或填过单价的模型，没填过单价的两个价格为 null。 */
    @Transactional(readOnly = true)
    public List<PriceView> prices() {
        var saved = new LinkedHashMap<String, ModelPrice>();
        store.prices().forEach(price -> saved.put(price.model(), price));
        var models = new TreeSet<>(store.usedModels());
        models.addAll(saved.keySet());
        return models.stream().map(model -> {
            var price = saved.get(model);
            return new PriceView(model, price == null ? null : price.inputPerMillion(),
                    price == null ? null : price.outputPerMillion());
        }).toList();
    }

    /** 保存一个模型的单价（元 / 百万 token），记一条审计。 */
    @Transactional
    public PriceView savePrice(TenantId tenantId, UserId actor, String model, @Nullable BigDecimal inputPerMillion,
            @Nullable BigDecimal outputPerMillion) {
        var name = model == null ? "" : model.strip();
        if (name.isEmpty() || name.length() > MAX_NAME_LENGTH) {
            throw invalid("模型名称必须是 1 到 " + MAX_NAME_LENGTH + " 个字符");
        }
        var input = checkPrice("输入单价", inputPerMillion);
        var output = checkPrice("输出单价", outputPerMillion);
        store.savePrice(new ModelPrice(name, input, output), OffsetDateTime.now(clock));
        audit.record(tenantId, actor, "MODEL_PRICE_CHANGED", "MODEL_PRICE", name,
                "修改模型 " + name + " 的单价：输入 " + input.toPlainString() + "、输出 " + output.toPlainString() + " 元/百万 token");
        return new PriceView(name, input, output);
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(zones.statsZone()));
    }

    private LocalDate date(@Nullable String value, LocalDate fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return LocalDate.parse(value.strip());
        } catch (DateTimeParseException e) {
            throw invalid("日期要写成 yyyy-MM-dd");
        }
    }

    private static BigDecimal checkPrice(String label, @Nullable BigDecimal value) {
        if (value == null || value.signum() < 0 || value.compareTo(MAX_PRICE) > 0) {
            throw invalid(label + "必须是 0 到 " + MAX_PRICE.toPlainString() + " 之间的数");
        }
        return value.setScale(4, RoundingMode.HALF_UP);
    }

    private static String key(UsageGroupBy by, UsageRow row) {
        return switch (by) {
            case DETAIL -> row.day() + " " + row.purpose() + " " + row.provider() + " " + row.model();
            case DAY -> row.day().toString();
            case MONTH -> row.day().toString().substring(0, 7);
            case PURPOSE -> row.purpose();
            case MODEL -> row.model() + " · " + row.provider();
        };
    }

    /** 只留域名（带端口时含端口）：地址里可能带路径，也不该把带密钥的地址存下来。 */
    private static String provider(String baseUrl) {
        try {
            var uri = URI.create(baseUrl);
            if (uri.getHost() != null) {
                return cut(uri.getPort() < 0 ? uri.getHost() : uri.getHost() + ":" + uri.getPort(), MAX_NAME_LENGTH);
            }
        } catch (IllegalArgumentException e) {
            // 地址写得不规范，下面按“未知”记。
        }
        return "未知";
    }

    private static String cut(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static SettingsException invalid(String message) {
        return new SettingsException(SettingsException.Reason.INVALID, message);
    }

    /** 一组的累计：调用数、token、费用，以及是否有没填单价的用量。 */
    private static final class Tally {
        private final @Nullable UsageRow detail;
        private long calls;
        private long input;
        private long output;
        private BigDecimal cost = BigDecimal.ZERO;
        private boolean unpriced;

        Tally(@Nullable UsageRow detail) {
            this.detail = detail;
        }

        void add(UsageRow row, @Nullable ModelPrice price) {
            calls += row.calls();
            input += row.inputTokens();
            output += row.outputTokens();
            if (price == null) {
                unpriced |= row.inputTokens() + row.outputTokens() > 0;
                return;
            }
            cost = cost.add(price.inputPerMillion().multiply(BigDecimal.valueOf(row.inputTokens()))
                    .add(price.outputPerMillion().multiply(BigDecimal.valueOf(row.outputTokens())))
                    .divide(MILLION, 6, RoundingMode.HALF_UP));
        }

        UsageGroup group(String key) {
            var scaled = cost.setScale(4, RoundingMode.HALF_UP);
            if (detail == null) {
                return new UsageGroup(key, null, null, null, calls, input, output, scaled, unpriced);
            }
            return new UsageGroup(detail.day().toString(), detail.purpose(), detail.provider(), detail.model(), calls,
                    input, output, scaled, unpriced);
        }
    }
}
