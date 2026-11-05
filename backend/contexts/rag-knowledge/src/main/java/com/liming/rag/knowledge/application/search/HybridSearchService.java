package com.liming.rag.knowledge.application.search;

import com.liming.rag.knowledge.application.ingestion.DocumentCalendar;
import com.liming.rag.knowledge.application.Grantee;
import com.liming.rag.knowledge.application.ChunkIndex;

import com.liming.rag.ai.EmbeddingClient;
import com.liming.rag.identity.api.UserDirectory;
import com.liming.rag.knowledge.api.KnowledgeSearch;
import com.liming.rag.knowledge.api.SearchHit;
import com.liming.rag.knowledge.api.SearchQuery;
import com.liming.rag.knowledge.domain.RrfFusion;
import com.liming.rag.shared.TenantId;
import com.liming.rag.shared.UserId;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class HybridSearchService implements KnowledgeSearch {

    /** 每路召回的候选数。 */
    static final int CANDIDATES_PER_ROUTE = 50;
    static final int MAX_LIMIT = 50;

    private final ChunkIndex index;
    private final EmbeddingClient embeddings;
    private final UserDirectory users;
    private final DocumentCalendar calendar;

    HybridSearchService(ChunkIndex index, EmbeddingClient embeddings, UserDirectory users, DocumentCalendar calendar) {
        this.index = index;
        this.embeddings = embeddings;
        this.users = users;
        this.calendar = calendar;
    }

    /** 提问人所属的角色由这里向 identity 查，调用方不能指定（D-038）。 */
    private Grantee grantee(TenantId tenantId, UserId userId) {
        return new Grantee(tenantId, userId, users.roleIds(tenantId, userId));
    }

    /** 不开事务：先给问题向量化（外部调用），再查库；每次查询各自读取，不占着连接等模型（#135）。 */
    @Override
    public List<SearchHit> search(SearchQuery query) {
        var text = query.text() == null ? "" : query.text().strip();
        if (text.isEmpty()) {
            return List.of();
        }
        int limit = Math.clamp(query.limit(), 1, MAX_LIMIT);
        var vector = embeddings.embedQuestion(text);
        var grantee = grantee(query.tenantId(), query.userId());
        // 未生效、已失效的文档不参与检索（D-046）；问答、追问、重新回答都经过这里。
        var today = calendar.today();
        var semantic = index.nearest(grantee, query.knowledgeBaseIds(), today, vector, CANDIDATES_PER_ROUTE);
        var keyword = index.keyword(grantee, query.knowledgeBaseIds(), today, text, CANDIDATES_PER_ROUTE);
        var fused = RrfFusion.fuse(List.of(semantic, keyword), limit);
        if (fused.isEmpty()) {
            return List.of();
        }
        var details = index.load(query.tenantId(), fused.stream().map(RrfFusion.Scored::id).toList()).stream()
                .collect(Collectors.toMap(SearchHit::chunkId, Function.identity()));
        return fused.stream()
                .filter(scored -> details.containsKey(scored.id()))
                .map(scored -> {
                    var hit = details.get(scored.id());
                    return new SearchHit(hit.chunkId(), hit.documentId(), hit.documentName(), hit.headingPath(),
                            hit.content(), scored.score());
                })
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Map<Long, String> excerpts(TenantId tenantId, UserId userId, Collection<Long> chunkIds) {
        if (chunkIds.isEmpty()) {
            return Map.of();
        }
        return index.accessibleContents(grantee(tenantId, userId), List.copyOf(chunkIds));
    }
}
