package com.liming.rag.knowledge.application;

import com.liming.rag.common.TenantId;
import com.liming.rag.knowledge.domain.KnowledgeBase;
import com.liming.rag.knowledge.domain.KnowledgeBaseRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class KnowledgeBaseService {

    private final KnowledgeBaseRepository knowledgeBases;

    public KnowledgeBaseService(KnowledgeBaseRepository knowledgeBases) {
        this.knowledgeBases = knowledgeBases;
    }

    @Transactional
    public KnowledgeBase create(TenantId tenantId, String name) {
        KnowledgeBase knowledgeBase;
        try {
            knowledgeBase = KnowledgeBase.create(tenantId, name);
        } catch (IllegalArgumentException e) {
            throw new KnowledgeException(KnowledgeException.Reason.INVALID, e.getMessage());
        }
        if (knowledgeBases.existsByName(tenantId, knowledgeBase.name())) {
            throw new KnowledgeException(KnowledgeException.Reason.CONFLICT, "已有同名知识库：" + knowledgeBase.name());
        }
        return knowledgeBases.save(knowledgeBase);
    }

    @Transactional(readOnly = true)
    public List<KnowledgeBase> list(TenantId tenantId) {
        return knowledgeBases.list(tenantId);
    }

    @Transactional(readOnly = true)
    public KnowledgeBase get(TenantId tenantId, long id) {
        return knowledgeBases.find(tenantId, id)
                .orElseThrow(() -> new KnowledgeException(KnowledgeException.Reason.NOT_FOUND, "知识库不存在"));
    }
}
