package com.liming.rag.knowledge.application;

import com.liming.rag.identity.api.PermissionCatalog;
import com.liming.rag.knowledge.api.KnowledgePermissions;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/** 把 {@link KnowledgePermissions} 交给 identity 汇总（#136）。 */
@Component
class KnowledgePermissionCatalog implements PermissionCatalog {

    @Override
    public List<Section> sections() {
        return KnowledgePermissions.SECTIONS;
    }

    @Override
    public Set<String> adminDefaults() {
        return KnowledgePermissions.ADMIN_DEFAULTS;
    }
}
