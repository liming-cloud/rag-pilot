package com.liming.rag.answering.application;

import com.liming.rag.ai.EmbeddingClient;
import com.liming.rag.ai.ModelCallException;
import com.liming.rag.answering.application.insight.AnswerStatistics;
import com.liming.rag.answering.application.insight.GapRecords;
import com.liming.rag.answering.application.insight.UsageStats;
import com.liming.rag.answering.application.review.AnswerReview;
import com.liming.rag.answering.application.review.AnswerReviews;
import com.liming.rag.answering.domain.AnswerRecord;
import com.liming.rag.answering.domain.AnswerRecordRepository;
import com.liming.rag.answering.domain.Conversation;
import com.liming.rag.answering.domain.ConversationRepository;
import com.liming.rag.answering.domain.ConversationTurn;
import com.liming.rag.identity.api.UserDirectory;
import com.liming.rag.knowledge.api.KnowledgeAccess;
import com.liming.rag.knowledge.api.KnowledgeBaseRef;
import com.liming.rag.knowledge.api.KnowledgeSearch;
import com.liming.rag.knowledge.api.SearchHit;
import com.liming.rag.knowledge.api.SearchQuery;
import com.liming.rag.shared.TenantId;
import com.liming.rag.shared.UserId;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * answering 应用服务单元测试用的内存实现：会话（带版本号和主线末尾，口径同 {@code MybatisConversationRepository}）、
 * 回答记录、管理端和统计的查询端口、knowledge 的授权与引用原文、identity 的账号目录和嵌入模型。
 */
public final class InMemoryAnswering {

    public static final TenantId TENANT = new TenantId(1);

    public final Conversations conversations = new Conversations();
    public final Records records = new Records();
    public final Reviews reviews = new Reviews();
    public final Gaps gaps = new Gaps();
    public final Statistics statistics = new Statistics();
    public final Access access = new Access();
    public final Directory users = new Directory();
    public final Embeddings embeddings = new Embeddings();

    /** 会话和轮次；保存时按版本号判断并发修改。 */
    public static final class Conversations implements ConversationRepository {

        public final Map<Long, Conversation> rows = new LinkedHashMap<>();
        public final Map<Long, ConversationTurn> turns = new LinkedHashMap<>();
        public final Set<Long> superseded = new HashSet<>();
        private final AtomicLong ids = new AtomicLong(10);
        private final AtomicLong turnIds = new AtomicLong(1000);
        /** 模拟别的操作抢先改过会话：下几次更新时版本不一致。 */
        public int staleUpdates;

        @Override
        public Conversation save(Conversation conversation) {
            if (conversation.id() == null) {
                var saved = conversation.withId(ids.incrementAndGet());
                rows.put(saved.id(), saved);
                return saved;
            }
            var current = rows.get(conversation.id());
            if (staleUpdates > 0 || current == null || current.version() != conversation.version()) {
                staleUpdates = Math.max(0, staleUpdates - 1);
                throw new Conversation.ConcurrentChangeException();
            }
            var saved = conversation.withVersion(conversation.version() + 1);
            rows.put(saved.id(), saved);
            return saved;
        }

        @Override
        public Optional<Conversation> find(TenantId tenantId, long id) {
            // 每次都按库里的状态重新恢复一个聚合，和从数据库读一样，不共享对象。
            return Optional.ofNullable(rows.get(id)).filter(c -> c.tenantId().equals(tenantId))
                    .map(c -> Conversation.restore(c.id(), c.tenantId(), c.owner(), c.version(), c.title(),
                            c.titleSource(), c.knowledgeBaseIds(), c.lastAskedAt(), c.tail()));
        }

        @Override
        public List<Conversation> listOwned(TenantId tenantId, UserId owner) {
            return rows.values().stream().filter(c -> c.tenantId().equals(tenantId) && c.owner().equals(owner))
                    .toList();
        }

        @Override
        public void delete(TenantId tenantId, long id) {
            rows.remove(id);
        }

        @Override
        public int deleteInactiveSince(Instant before) {
            int before0 = rows.size();
            rows.values().removeIf(c -> c.lastAskedAt().isBefore(before));
            return before0 - rows.size();
        }

        @Override
        public long nextTurnId() {
            return turnIds.incrementAndGet();
        }

        @Override
        public void addTurn(long conversationId, ConversationTurn turn, Long replacesTurnId) {
            if (replacesTurnId != null) {
                superseded.add(replacesTurnId);
            }
            turns.put(turn.id(), turn);
        }

        @Override
        public List<ConversationTurn> mainLine(long conversationId) {
            var conversation = rows.get(conversationId);
            var chain = new ArrayList<ConversationTurn>();
            Long next = conversation == null || conversation.tail() == null ? null : conversation.tail().turnId();
            while (next != null) {
                var turn = turns.get(next);
                chain.addFirst(turn);
                next = turn.parentTurnId();
            }
            return chain;
        }
    }

    /** 回答记录：按聚合存，评价和处理状态分开保存，口径同 {@code MybatisAnswerRecordRepository}。 */
    public static final class Records implements AnswerRecordRepository {

        public final Map<Long, AnswerRecord> rows = new LinkedHashMap<>();
        private final AtomicLong ids = new AtomicLong(200);
        public int reviewSaves;

        @Override
        public AnswerRecord add(AnswerRecord record) {
            var saved = record.withId(ids.incrementAndGet());
            rows.put(saved.id(), copy(saved));
            return saved;
        }

        @Override
        public Optional<AnswerRecord> find(TenantId tenantId, long id) {
            return Optional.ofNullable(rows.get(id)).filter(row -> row.tenantId().equals(tenantId))
                    .map(Records::copy);
        }

        @Override
        public Map<Long, AnswerRecord> findAll(TenantId tenantId, Collection<Long> ids) {
            var result = new HashMap<Long, AnswerRecord>();
            ids.forEach(id -> find(tenantId, id).ifPresent(record -> result.put(id, record)));
            return result;
        }

        @Override
        public void saveFeedback(AnswerRecord record) {
            var row = rows.get(record.id());
            rows.put(record.id(), AnswerRecord.restore(row.id(), row.tenantId(), row.asker(), row.question(),
                    row.reply(), row.knowledgeBaseIds(), record.rating(), record.comment(), row.reviewStatus(),
                    row.resolutionNote(), row.resolvedBy()));
        }

        @Override
        public void saveReview(AnswerRecord record) {
            reviewSaves++;
            var row = rows.get(record.id());
            rows.put(record.id(), AnswerRecord.restore(row.id(), row.tenantId(), row.asker(), row.question(),
                    row.reply(), row.knowledgeBaseIds(), row.rating(), row.comment(), record.reviewStatus(),
                    record.resolutionNote(), record.resolvedBy()));
        }

        public int batchSaves;

        @Override
        public void saveReviews(Collection<AnswerRecord> records) {
            batchSaves++;
            records.forEach(this::saveReview);
        }

        /** 每次都按库里的状态重新恢复一个聚合，不共享对象。 */
        private static AnswerRecord copy(AnswerRecord row) {
            return AnswerRecord.restore(row.id(), row.tenantId(), row.asker(), row.question(), row.reply(),
                    row.knowledgeBaseIds(), row.rating(), row.comment(), row.reviewStatus(), row.resolutionNote(),
                    row.resolvedBy());
        }
    }

    /** 管理端的回答记录查询：记下查询条件，返回测试给出的行。 */
    public static final class Reviews implements AnswerReviews {

        public AnswerReview.Query query;
        public List<AnswerReview> rows = List.of();
        public OffsetDateTime from;
        public OffsetDateTime to;

        @Override
        public Paged<AnswerReview> page(TenantId tenantId, AnswerReview.Query query) {
            this.query = query;
            return new Paged<>(rows, rows.size(), query.page().page(), query.page().size());
        }

        @Override
        public List<AnswerReview> between(TenantId tenantId, OffsetDateTime from, OffsetDateTime to, int limit) {
            this.from = from;
            this.to = to;
            return rows;
        }
    }

    /** 知识缺口的原始记录：测试给出，按时间倒序（与 SQL 一致）。 */
    public static final class Gaps implements GapRecords {

        public List<GapRecord> rows = List.of();
        public long total = -1;

        @Override
        public List<GapRecord> list(TenantId tenantId, OffsetDateTime from, OffsetDateTime to, int limit) {
            return rows.stream().limit(limit).toList();
        }

        @Override
        public long count(TenantId tenantId, OffsetDateTime from, OffsetDateTime to) {
            return total < 0 ? rows.size() : total;
        }
    }

    /** 使用统计：记下查询的日期范围，返回空的统计。 */
    public static final class Statistics implements AnswerStatistics {

        public final List<LocalDate[]> usageCalls = new ArrayList<>();
        public final List<LocalDate[]> totalsCalls = new ArrayList<>();

        @Override
        public UsageStats usage(TenantId tenantId, LocalDate from, LocalDate to, ZoneId zone) {
            usageCalls.add(new LocalDate[] {from, to});
            return new UsageStats(from, to, zone.getId(), totals(), null, List.of(), List.of(), List.of(), List.of());
        }

        @Override
        public UsageStats.Totals totals(TenantId tenantId, LocalDate from, LocalDate to, ZoneId zone) {
            totalsCalls.add(new LocalDate[] {from, to});
            return totals();
        }

        private static UsageStats.Totals totals() {
            return new UsageStats.Totals(0, 0, 0, 0, 0, 0, 0);
        }
    }

    /** knowledge 的授权和引用原文：每个账号能访问哪些库，哪些分块的原文还有权查看。 */
    public static final class Access implements KnowledgeAccess, KnowledgeSearch {

        public final Map<Long, List<KnowledgeBaseRef>> accessible = new HashMap<>();
        public final Map<Long, String> names = new HashMap<>();
        public final Map<Long, String> excerpts = new HashMap<>();

        public Access grant(long userId, long knowledgeBaseId, String name) {
            names.put(knowledgeBaseId, name);
            accessible.computeIfAbsent(userId, key -> new ArrayList<>()).add(new KnowledgeBaseRef(knowledgeBaseId,
                    name));
            return this;
        }

        public void revoke(long userId, long knowledgeBaseId) {
            accessible.getOrDefault(userId, new ArrayList<>()).removeIf(ref -> ref.id() == knowledgeBaseId);
        }

        @Override
        public List<KnowledgeBaseRef> accessible(TenantId tenantId, UserId userId) {
            return List.copyOf(accessible.getOrDefault(userId.value(), List.of()));
        }

        @Override
        public List<Long> scope(TenantId tenantId, UserId userId, Collection<Long> requested) {
            var ids = accessible(tenantId, userId).stream().map(KnowledgeBaseRef::id).toList();
            if (requested == null || requested.isEmpty()) {
                return ids;
            }
            return requested.stream().distinct().filter(ids::contains).toList();
        }

        @Override
        public Map<Long, String> names(TenantId tenantId, Collection<Long> knowledgeBaseIds) {
            var result = new HashMap<Long, String>();
            knowledgeBaseIds.stream().filter(names::containsKey).forEach(id -> result.put(id, names.get(id)));
            return result;
        }

        @Override
        public List<SearchHit> search(SearchQuery query) {
            return List.of();
        }

        @Override
        public Map<Long, String> excerpts(TenantId tenantId, UserId userId, Collection<Long> chunkIds) {
            var result = new HashMap<Long, String>();
            chunkIds.stream().filter(excerpts::containsKey).forEach(id -> result.put(id, excerpts.get(id)));
            return result;
        }
    }

    /** identity 的账号目录：显示名。 */
    public static final class Directory implements UserDirectory {

        public final Map<Long, String> names = new HashMap<>();

        @Override
        public boolean exists(TenantId tenantId, UserId userId) {
            return names.containsKey(userId.value());
        }

        @Override
        public List<Long> roleIds(TenantId tenantId, UserId userId) {
            return List.of();
        }

        @Override
        public boolean isSuperAdmin(TenantId tenantId, UserId userId) {
            return false;
        }

        @Override
        public boolean roleExists(TenantId tenantId, long roleId) {
            return false;
        }

        @Override
        public Map<Long, UserSummary> summaries(TenantId tenantId, Collection<Long> userIds) {
            var result = new HashMap<Long, UserSummary>();
            userIds.stream().filter(names::containsKey)
                    .forEach(id -> result.put(id, new UserSummary(id, "user" + id, names.get(id))));
            return result;
        }
    }

    /** 嵌入模型：按测试给的“话题”生成向量，同一话题的问题向量相同；也可以模拟模型不可用。 */
    public static final class Embeddings implements EmbeddingClient {

        public final Map<String, float[]> topics = new LinkedHashMap<>();
        public boolean unavailable;
        public int calls;

        public Embeddings topic(String keyword, float... vector) {
            topics.put(keyword, vector);
            return this;
        }

        @Override
        public List<float[]> embed(List<String> texts) {
            calls++;
            if (unavailable) {
                throw new ModelCallException("嵌入模型不可用");
            }
            return texts.stream().map(text -> topics.entrySet().stream()
                    .filter(entry -> text.contains(entry.getKey())).map(Map.Entry::getValue).findFirst()
                    .orElse(new float[] {0, 0, 1})).toList();
        }
    }
}
