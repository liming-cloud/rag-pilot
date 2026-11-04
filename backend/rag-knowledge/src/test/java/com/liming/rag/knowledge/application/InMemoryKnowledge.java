package com.liming.rag.knowledge.application;

import com.liming.rag.identity.api.UserDirectory;
import com.liming.rag.knowledge.api.SearchHit;
import com.liming.rag.knowledge.domain.Chunk;
import com.liming.rag.knowledge.domain.Document;
import com.liming.rag.knowledge.domain.DocumentRepository;
import com.liming.rag.knowledge.domain.DocumentStatus;
import com.liming.rag.knowledge.domain.KnowledgeBase;
import com.liming.rag.knowledge.domain.KnowledgeBaseRepository;
import com.liming.rag.knowledge.domain.Validity;
import com.liming.rag.settings.api.StatsZone;
import com.liming.rag.shared.TenantId;
import com.liming.rag.shared.UserId;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * knowledge 应用服务单元测试用的内存实现：知识库与授权、文档、原始文件、管理端查询、分块索引和 identity 的账号目录。
 * 规则只保留测试要用到的部分，授权的口径与 {@code ChunkSearchMapper.accessibleKnowledgeBases} 一致：
 * 直接授权给本人的 ∪ 授权给其角色的。
 */
final class InMemoryKnowledge {

    static final TenantId TENANT = new TenantId(1);
    static final TenantId OTHER_TENANT = new TenantId(2);
    /** 测试里的“今天”：2026-11-04（Asia/Shanghai）。 */
    static final LocalDate TODAY = LocalDate.of(2026, 11, 4);

    final KnowledgeBases knowledgeBases = new KnowledgeBases();
    final Documents documents = new Documents();
    final Files files = new Files();
    final Queries queries = new Queries();
    final Index index = new Index();
    final Directory users = new Directory();
    final DocumentCalendar calendar = calendar();

    private static DocumentCalendar calendar() {
        StatsZone settings = () -> ZoneId.of("Asia/Shanghai");
        var properties = new KnowledgeProperties(new KnowledgeProperties.Ingestion(2),
                new KnowledgeProperties.Storage("./data"), 30);
        return new DocumentCalendar(settings, properties,
                Clock.fixed(Instant.parse("2026-11-04T02:00:00Z"), ZoneOffset.UTC));
    }

    /** 知识库、直接授权和角色授权。 */
    final class KnowledgeBases implements KnowledgeBaseRepository {

        final Map<Long, KnowledgeBase> rows = new LinkedHashMap<>();
        final Map<Long, Set<Long>> members = new HashMap<>();
        final Map<Long, Set<Long>> roleGrants = new HashMap<>();
        private final AtomicLong ids = new AtomicLong(100);
        /** 模拟并发时唯一约束冲突：下一次保存时抛出。 */
        RuntimeException failNextSave;

        @Override
        public KnowledgeBase save(KnowledgeBase knowledgeBase) {
            if (failNextSave != null) {
                var failure = failNextSave;
                failNextSave = null;
                throw failure;
            }
            var saved = knowledgeBase.id() == null ? knowledgeBase.withId(ids.incrementAndGet()) : knowledgeBase;
            rows.put(saved.id(), saved);
            return saved;
        }

        @Override
        public Optional<KnowledgeBase> find(TenantId tenantId, long id) {
            return Optional.ofNullable(rows.get(id)).filter(kb -> kb.tenantId().equals(tenantId));
        }

        @Override
        public List<KnowledgeBase> listAccessible(TenantId tenantId, UserId userId, List<Long> roleIds) {
            return rows.values().stream()
                    .filter(kb -> kb.tenantId().equals(tenantId))
                    .filter(kb -> members.getOrDefault(kb.id(), Set.of()).contains(userId.value())
                            || roleGrants.getOrDefault(kb.id(), Set.of()).stream().anyMatch(roleIds::contains))
                    .toList();
        }

        @Override
        public List<KnowledgeBase> findAll(TenantId tenantId, Collection<Long> ids) {
            return rows.values().stream().filter(kb -> kb.tenantId().equals(tenantId) && ids.contains(kb.id()))
                    .toList();
        }

        @Override
        public void addMember(TenantId tenantId, long knowledgeBaseId, UserId userId) {
            members.computeIfAbsent(knowledgeBaseId, key -> new LinkedHashSet<>()).add(userId.value());
        }

        @Override
        public void removeMember(TenantId tenantId, long knowledgeBaseId, UserId userId) {
            members.getOrDefault(knowledgeBaseId, new HashSet<>()).remove(userId.value());
        }

        @Override
        public List<UserId> members(TenantId tenantId, long knowledgeBaseId) {
            return members.getOrDefault(knowledgeBaseId, Set.of()).stream().map(UserId::new).toList();
        }

        @Override
        public void addRoleGrant(TenantId tenantId, long knowledgeBaseId, long roleId) {
            roleGrants.computeIfAbsent(knowledgeBaseId, key -> new LinkedHashSet<>()).add(roleId);
        }

        @Override
        public void removeRoleGrant(TenantId tenantId, long knowledgeBaseId, long roleId) {
            roleGrants.getOrDefault(knowledgeBaseId, new HashSet<>()).remove(roleId);
        }

        @Override
        public List<Long> roleGrants(TenantId tenantId, long knowledgeBaseId) {
            return List.copyOf(roleGrants.getOrDefault(knowledgeBaseId, Set.of()));
        }

        @Override
        public void removeGrantsOfRole(TenantId tenantId, long roleId) {
            roleGrants.values().forEach(grants -> grants.remove(roleId));
        }

        @Override
        public boolean existsByName(TenantId tenantId, String name) {
            return rows.values().stream().anyMatch(kb -> kb.tenantId().equals(tenantId) && kb.name().equals(name));
        }
    }

    /** 文档和分块。 */
    final class Documents implements DocumentRepository {

        final Map<Long, Document> rows = new LinkedHashMap<>();
        final Map<Long, List<Chunk>> chunks = new HashMap<>();
        private final AtomicLong ids = new AtomicLong(500);
        /** 模拟并发时唯一约束冲突：下一次插入新文档时抛出。 */
        RuntimeException failNextInsert;

        @Override
        public void save(Document document) {
            if (document.id() == null) {
                if (failNextInsert != null) {
                    var failure = failNextInsert;
                    failNextInsert = null;
                    throw failure;
                }
                document.assignId(ids.incrementAndGet());
            }
            rows.put(document.id(), document);
        }

        @Override
        public void saveChunks(Document document, List<Chunk> values, List<float[]> embeddings) {
            chunks.put(document.id(), List.copyOf(values));
        }

        @Override
        public boolean existsInKnowledgeBase(long knowledgeBaseId, String sha256) {
            return rows.values().stream()
                    .anyMatch(doc -> doc.knowledgeBaseId() == knowledgeBaseId && doc.sha256().equals(sha256));
        }

        @Override
        public List<Document> list(TenantId tenantId, long knowledgeBaseId) {
            return rows.values().stream()
                    .filter(doc -> doc.tenantId().equals(tenantId) && doc.knowledgeBaseId() == knowledgeBaseId)
                    .toList();
        }

        @Override
        public Optional<Document> find(TenantId tenantId, long id) {
            return Optional.ofNullable(rows.get(id)).filter(doc -> doc.tenantId().equals(tenantId));
        }

        @Override
        public boolean delete(TenantId tenantId, long id) {
            var removed = find(tenantId, id).isPresent() && rows.remove(id) != null;
            chunks.remove(id);
            return removed;
        }

        @Override
        public boolean existsBySha256(TenantId tenantId, String sha256) {
            return rows.values().stream().anyMatch(doc -> doc.tenantId().equals(tenantId)
                    && doc.sha256().equals(sha256));
        }

        @Override
        public List<Document> listParsing() {
            return rows.values().stream().filter(doc -> doc.status() == DocumentStatus.PARSING).toList();
        }

        /** 直接放一份已就绪的文档。 */
        Document ready(long knowledgeBaseId, String filename, String sha256, Validity validity) {
            var document = Document.received(TENANT, knowledgeBaseId, filename, "text/plain", sha256, 10, null,
                    validity);
            save(document);
            document.markReady(1);
            return document;
        }
    }

    /** 原始文件，按租户和摘要存放。 */
    static final class Files implements FileStorage {

        final Map<String, byte[]> stored = new HashMap<>();

        @Override
        public void store(TenantId tenantId, String sha256, byte[] content) {
            stored.put(key(tenantId, sha256), content.clone());
        }

        @Override
        public byte[] read(TenantId tenantId, String sha256) {
            return Objects.requireNonNull(stored.get(key(tenantId, sha256)), "文件不存在");
        }

        @Override
        public void delete(TenantId tenantId, String sha256) {
            stored.remove(key(tenantId, sha256));
        }

        boolean has(TenantId tenantId, String sha256) {
            return stored.containsKey(key(tenantId, sha256));
        }

        private static String key(TenantId tenantId, String sha256) {
            return tenantId.value() + "/" + sha256;
        }
    }

    /** 管理端查询：只记下调用参数，返回空页；分页和筛选的 SQL 由 rag-app 的集成测试覆盖。 */
    static final class Queries implements KnowledgeQueries {

        Grantee grantee;
        boolean superAdminView;
        String keyword;
        DocumentStatus status;
        Validity.State validity;
        Window window;
        Paged.Request page;
        int limit;

        @Override
        public Paged<KnowledgeBaseSummary> knowledgeBases(TenantId tenantId, Grantee grantee, String keyword,
                Validity.State validity, Window window, Paged.Request page) {
            record(grantee, keyword, null, validity, window, page);
            return new Paged<>(List.of(), 0, page.page(), page.size());
        }

        @Override
        public Paged<DocumentRow> documents(TenantId tenantId, long knowledgeBaseId, String keyword,
                DocumentStatus status, Validity.State validity, Window window, Paged.Request page) {
            record(null, keyword, status, validity, window, page);
            return new Paged<>(List.of(), 0, page.page(), page.size());
        }

        @Override
        public ExpirySummary expirySummary(TenantId tenantId, Grantee grantee, Window window, int limit) {
            record(grantee, null, null, null, window, null);
            this.limit = limit;
            return new ExpirySummary(0, 0, List.of());
        }

        private void record(Grantee grantee, String keyword, DocumentStatus status, Validity.State validity,
                Window window, Paged.Request page) {
            this.grantee = grantee;
            this.superAdminView = grantee == null;
            this.keyword = keyword;
            this.status = status;
            this.validity = validity;
            this.window = window;
            this.page = page;
        }
    }

    /** 分块索引：两路召回的结果由测试给出，记下查询时用的授权范围和日期。 */
    static final class Index implements ChunkIndex {

        List<Long> semantic = List.of();
        List<Long> keyword = List.of();
        final Map<Long, SearchHit> hits = new HashMap<>();
        final Map<Long, String> contents = new HashMap<>();
        Grantee grantee;
        LocalDate today;
        int limit;
        final List<String> keywordTexts = new ArrayList<>();

        @Override
        public List<Long> nearest(Grantee grantee, List<Long> knowledgeBaseIds, LocalDate today, float[] vector,
                int limit) {
            this.grantee = grantee;
            this.today = today;
            this.limit = limit;
            return semantic;
        }

        @Override
        public List<Long> keyword(Grantee grantee, List<Long> knowledgeBaseIds, LocalDate today, String text,
                int limit) {
            keywordTexts.add(text);
            return keyword;
        }

        @Override
        public List<SearchHit> load(TenantId tenantId, List<Long> chunkIds) {
            return chunkIds.stream().filter(hits::containsKey).map(hits::get).toList();
        }

        @Override
        public Map<Long, String> accessibleContents(Grantee grantee, List<Long> chunkIds) {
            this.grantee = grantee;
            var result = new HashMap<Long, String>();
            chunkIds.stream().filter(contents::containsKey).forEach(id -> result.put(id, contents.get(id)));
            return result;
        }
    }

    /** identity 的账号目录：存在的账号、各账号的角色、超级管理员、存在的角色。 */
    static final class Directory implements UserDirectory {

        final Map<Long, List<Long>> roles = new HashMap<>();
        final Set<Long> superAdmins = new HashSet<>();
        final Set<Long> existingRoles = new HashSet<>();
        final Map<Long, String> names = new HashMap<>();

        Directory user(long id, String displayName, Long... roleIds) {
            names.put(id, displayName);
            roles.put(id, List.of(roleIds));
            return this;
        }

        @Override
        public boolean exists(TenantId tenantId, UserId userId) {
            return tenantId.equals(TENANT) && names.containsKey(userId.value());
        }

        @Override
        public List<Long> roleIds(TenantId tenantId, UserId userId) {
            return roles.getOrDefault(userId.value(), List.of());
        }

        @Override
        public boolean isSuperAdmin(TenantId tenantId, UserId userId) {
            return superAdmins.contains(userId.value());
        }

        @Override
        public boolean roleExists(TenantId tenantId, long roleId) {
            return existingRoles.contains(roleId);
        }

        @Override
        public Map<Long, UserSummary> summaries(TenantId tenantId, Collection<Long> userIds) {
            var result = new HashMap<Long, UserSummary>();
            userIds.stream().filter(names::containsKey)
                    .forEach(id -> result.put(id, new UserSummary(id, "user" + id, names.get(id))));
            return result;
        }
    }
}
