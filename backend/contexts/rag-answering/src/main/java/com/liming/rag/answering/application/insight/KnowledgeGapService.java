package com.liming.rag.answering.application.insight;

import com.liming.rag.answering.application.AnsweringException;
import com.liming.rag.answering.application.AnsweringProperties;
import com.liming.rag.answering.application.ModelUnavailableException;
import com.liming.rag.answering.application.insight.GapRecords.GapRecord;
import com.liming.rag.answering.domain.AnswerRecord;
import com.liming.rag.answering.domain.AnswerRecordRepository;
import com.liming.rag.identity.api.UserDirectory;
import com.liming.rag.knowledge.api.KnowledgeAccess;
import com.liming.rag.knowledge.api.KnowledgeBaseRef;
import com.liming.rag.settings.api.StatsZone;
import com.liming.rag.shared.TenantId;
import com.liming.rag.shared.UserId;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.IntStream;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 知识缺口（#125、D-047）：把一段时间内答不上来的和被点踩的问题按意思归类，按待处理次数排序，告诉总部该补哪些资料。
 * 查询时归类、不落表：取范围内最近的 1000 条，按问题向量归类（{@link QuestionClusters}）。
 * 问题向量在记录回答后由 {@link QuestionVectorRecorder} 存好（#145 C6）；还没有的（旧记录、后台没算成）这里补算并写回，
 * 嵌入模型不可用时退回按原文分组。不调用对话模型。
 *
 * <p>查询报告不开事务：向量化是外部调用，不能占着数据库连接等它（#135）；几次读之间不要求同一快照。
 */
@Service
public class KnowledgeGapService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeGapService.class);

    /** 一次最多归类多少条记录。 */
    static final int MAX_RECORDS = 1000;
    /** 最多看多少天。 */
    static final int MAX_DAYS = 90;
    /** 整类标记已处理时一次最多多少条。 */
    static final int MAX_RESOLVE = MAX_RECORDS;

    private final GapRecords gapRecords;
    private final AnswerRecordRepository records;
    private final QuestionEmbeddings embeddings;
    private final UserDirectory users;
    private final KnowledgeAccess access;
    private final StatsZone zones;
    private final AnsweringProperties properties;
    private final Clock clock;

    KnowledgeGapService(GapRecords gapRecords, AnswerRecordRepository records, QuestionEmbeddings embeddings,
            UserDirectory users, KnowledgeAccess access, StatsZone zones, AnsweringProperties properties, Clock clock) {
        this.gapRecords = gapRecords;
        this.records = records;
        this.embeddings = embeddings;
        this.users = users;
        this.access = access;
        this.zones = zones;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * 最近 {@code days} 天（含今天，按统计时区）的知识缺口。
     *
     * @param status open：只列还有待处理记录的类；all：全部
     * @throws AnsweringException 天数或状态不合法
     */
    public GapReport report(TenantId tenantId, int days, String status) {
        if (days < 1 || days > MAX_DAYS) {
            throw invalid("天数只能是 1 到 " + MAX_DAYS + " 天");
        }
        boolean openOnly = switch (status == null ? "" : status) {
            case "open" -> true;
            case "all" -> false;
            default -> throw invalid("处理状态只能是 open 或 all");
        };
        var zone = zones.statsZone();
        var today = LocalDate.now(clock.withZone(zone));
        var report = report(tenantId, today.minusDays(days - 1L), today, zone);
        if (!openOnly) {
            return report;
        }
        return new GapReport(report.from(), report.to(), report.zone(), report.grouping(), report.records(),
                report.truncated(), report.groups().stream().filter(group -> group.openCount() > 0).toList());
    }

    /** {@code from} 到 {@code to}（都含）的知识缺口，按待处理次数、总次数从多到少。 */
    public GapReport report(TenantId tenantId, LocalDate from, LocalDate to, ZoneId zone) {
        return report(tenantId, from, to, zone, true);
    }

    /** @param embedMissing 没有向量的原文是否现算（并写回）；运营概览不算（D-067）。 */
    private GapReport report(TenantId tenantId, LocalDate from, LocalDate to, ZoneId zone, boolean embedMissing) {
        var start = from.atStartOfDay(zone).toOffsetDateTime();
        var end = to.plusDays(1).atStartOfDay(zone).toOffsetDateTime();
        var found = gapRecords.list(tenantId, start, end, MAX_RECORDS);
        boolean truncated = found.size() == MAX_RECORDS && gapRecords.count(tenantId, start, end) > MAX_RECORDS;

        var byText = new LinkedHashMap<String, List<GapRecord>>();
        found.forEach(record -> byText.computeIfAbsent(record.question(), key -> new ArrayList<>()).add(record));
        // 出现多的问题先处理，它会成为所在类的代表问题；次数相同时最近问过的在前（found 已按时间倒序）。
        var texts = byText.keySet().stream()
                .sorted(Comparator.comparingInt((String text) -> byText.get(text).size()).reversed())
                .toList();
        var grouping = GapGrouping.SEMANTIC;
        List<List<Integer>> clusters;
        if (!embedMissing) {
            clusters = storedClusters(texts, byText);
        } else {
            try {
                clusters = QuestionClusters.bySimilarity(vectors(tenantId, texts, byText),
                        properties.gaps().similarity());
            } catch (ModelUnavailableException e) {
                log.warn("event=gap_grouping_degraded fallback=text error={}", e.code());
                grouping = GapGrouping.TEXT;
                clusters = QuestionClusters.byText(texts);
            }
        }

        var names = users.summaries(tenantId, found.stream().map(GapRecord::userId).distinct().toList());
        // 只用来显示名字；已删除的库没有名字，不显示。
        var knowledgeBaseNames = access.names(tenantId,
                found.stream().flatMap(record -> record.knowledgeBaseIds().stream()).distinct().toList());
        var groups = clusters.stream()
                .map(indexes -> group(indexes.stream().map(texts::get).toList(), byText, names, knowledgeBaseNames))
                .sorted(Comparator.comparingInt(GapGroup::openCount).thenComparingInt(GapGroup::count)
                        .thenComparing(GapGroup::lastAskedAt).reversed())
                .toList();
        return new GapReport(from, to, zone.getId(), grouping, found.size(), truncated, groups);
    }

    /** 每段原文的问题向量：用记录里存好的，没有的一次算完并写回这段原文的每条记录。 */
    private List<float[]> vectors(TenantId tenantId, List<String> texts, Map<String, List<GapRecord>> byText) {
        var known = stored(texts, byText);
        var missing = texts.stream().filter(text -> !known.containsKey(text)).toList();
        if (!missing.isEmpty()) {
            var computed = embeddings.embed(missing);
            for (int i = 0; i < missing.size(); i++) {
                known.put(missing.get(i), computed.get(i));
            }
        }
        var backfill = new HashMap<Long, float[]>();
        texts.forEach(text -> byText.get(text).stream().filter(record -> record.questionVector() == null)
                .forEach(record -> backfill.put(record.id(), known.get(text))));
        if (!backfill.isEmpty()) {
            gapRecords.saveVectors(tenantId, backfill);
        }
        return texts.stream().map(known::get).toList();
    }

    /** 每段原文记录里存好的向量（任一条有即可）；都没有的不在结果里。 */
    private static Map<String, float[]> stored(List<String> texts, Map<String, List<GapRecord>> byText) {
        var known = new HashMap<String, float[]>();
        texts.forEach(text -> byText.get(text).stream().map(GapRecord::questionVector).filter(Objects::nonNull)
                .findFirst().ifPresent(vector -> known.put(text, vector)));
        return known;
    }

    /** 不调模型的归类（D-067）：有存好向量的按意思归类，没有的按原文归类。 */
    private List<List<Integer>> storedClusters(List<String> texts, Map<String, List<GapRecord>> byText) {
        var known = stored(texts, byText);
        var withVector = IntStream.range(0, texts.size()).filter(i -> known.containsKey(texts.get(i))).boxed().toList();
        var without = IntStream.range(0, texts.size()).filter(i -> !known.containsKey(texts.get(i))).boxed().toList();
        var clusters = new ArrayList<List<Integer>>();
        QuestionClusters.bySimilarity(withVector.stream().map(i -> known.get(texts.get(i))).toList(),
                properties.gaps().similarity()).forEach(c -> clusters.add(c.stream().map(withVector::get).toList()));
        QuestionClusters.byText(without.stream().map(texts::get).toList())
                .forEach(c -> clusters.add(c.stream().map(without::get).toList()));
        return clusters;
    }

    /** 待处理次数最多的几类，供运营概览；只看还有待处理记录的类。只用已存的向量，不调模型、不写库（D-067）。 */
    public List<UsageStats.QuestionCount> top(TenantId tenantId, LocalDate from, LocalDate to, ZoneId zone,
            int limit) {
        return report(tenantId, from, to, zone, false).groups().stream()
                .filter(group -> group.openCount() > 0)
                .limit(limit)
                .map(group -> new UsageStats.QuestionCount(group.question(), group.openCount()))
                .toList();
    }

    /**
     * 整类标记已处理：这些记录里还没处理的都标记已处理，附同一条说明；已处理的不变。
     *
     * @return 这次标记的条数
     * @throws AnsweringException 没有记录、超过上限、有记录不存在，或说明太长
     */
    @Transactional
    public int resolve(TenantId tenantId, UserId by, List<Long> recordIds, String note) {
        if (recordIds == null || recordIds.isEmpty()) {
            throw invalid("没有要处理的记录");
        }
        if (recordIds.size() > MAX_RESOLVE) {
            throw invalid("一次最多处理 " + MAX_RESOLVE + " 条");
        }
        var ids = new LinkedHashSet<>(recordIds);
        var found = records.findAll(tenantId, ids);
        if (found.size() < ids.size()) {
            throw new AnsweringException(AnsweringException.Reason.NOT_FOUND, "回答不存在");
        }
        var open = found.values().stream()
                .filter(record -> record.reviewStatus() == AnswerRecord.ReviewStatus.OPEN).toList();
        open.forEach(record -> record.review(by, AnswerRecord.ReviewStatus.RESOLVED, note));
        // 一类可能有上千条：读一次、写一次，不逐条往返（#136）。
        records.saveReviews(open);
        return open.size();
    }

    private static GapGroup group(List<String> texts, Map<String, List<GapRecord>> byText,
            Map<Long, UserDirectory.UserSummary> names, Map<Long, String> knowledgeBaseNames) {
        var members = texts.stream().flatMap(text -> byText.get(text).stream())
                .sorted(Comparator.comparing(GapRecord::createdAt).reversed().thenComparing(GapRecord::id,
                        Comparator.reverseOrder()))
                .toList();
        var representative = texts.getFirst();
        var latestOfRepresentative = byText.get(representative).getFirst();
        var documents = new LinkedHashSet<String>();
        members.forEach(record -> documents.addAll(record.citedDocuments()));
        var knowledgeBaseCounts = members.stream().flatMap(record -> record.knowledgeBaseIds().stream())
                .filter(knowledgeBaseNames::containsKey)
                .collect(Collectors.groupingBy(id -> id, LinkedHashMap::new, Collectors.counting()));
        var knowledgeBases = knowledgeBaseCounts.entrySet().stream()
                .sorted(Map.Entry.<Long, Long>comparingByValue().reversed())
                .map(entry -> new KnowledgeBaseRef(entry.getKey(), knowledgeBaseNames.get(entry.getKey())))
                .toList();
        return new GapGroup(latestOfRepresentative.id(), representative, members.size(),
                (int) members.stream().filter(record -> record.reviewStatus() == AnswerRecord.ReviewStatus.OPEN).count(),
                (int) members.stream().filter(GapRecord::refused).count(),
                (int) members.stream().filter(record -> !record.refused()).count(),
                texts.size(), members.getFirst().createdAt(),
                knowledgeBases.stream().map(KnowledgeBaseRef::name).toList(), knowledgeBases, List.copyOf(documents),
                members.stream().map(record -> {
                    var asker = names.get(record.userId());
                    return new GapEntry(record.id(), record.question(), asker == null ? null : asker.displayName(),
                            record.createdAt(), record.refused(), record.comment(),
                            record.knowledgeBaseIds().stream().filter(knowledgeBaseNames::containsKey)
                                    .map(knowledgeBaseNames::get).toList(),
                            record.citedDocuments(), record.reviewStatus().name());
                }).toList());
    }

    private static AnsweringException invalid(String message) {
        return new AnsweringException(AnsweringException.Reason.INVALID, message);
    }
}
