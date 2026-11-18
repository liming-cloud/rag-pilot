package com.liming.rag.evaluation.application;

import com.liming.rag.knowledge.api.KnowledgeAccess;
import com.liming.rag.knowledge.api.KnowledgeBaseRef;
import com.liming.rag.shared.TenantId;
import com.liming.rag.shared.UserId;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 查看人、运行人能访问哪些知识库（D-070）：无权访问的库里的题当作不存在——不显示、不运行、不计分。
 * 口径同员工问答（{@link KnowledgeAccess#accessible}），管理员也只看到自己是成员的库（D-017）。
 */
@Component
public class Viewers {

    /** 能访问的库：id → 名称，按库的顺序。 */
    public record Viewer(TenantId tenantId, UserId userId, Map<Long, String> knowledgeBases) {

        public boolean sees(long knowledgeBaseId) {
            return knowledgeBases.containsKey(knowledgeBaseId);
        }

        public boolean sees(GoldenQuestion question) {
            return sees(question.knowledgeBaseId());
        }

        public String knowledgeBaseName(long knowledgeBaseId) {
            return knowledgeBases.getOrDefault(knowledgeBaseId, "");
        }
    }

    private final KnowledgeAccess access;

    Viewers(KnowledgeAccess access) {
        this.access = access;
    }

    public Viewer of(TenantId tenantId, UserId userId) {
        var bases = new LinkedHashMap<Long, String>();
        for (KnowledgeBaseRef base : access.accessible(tenantId, userId)) {
            bases.put(base.id(), base.name());
        }
        return new Viewer(tenantId, userId, bases);
    }
}
