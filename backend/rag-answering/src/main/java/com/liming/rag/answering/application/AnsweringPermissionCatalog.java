package com.liming.rag.answering.application;

import com.liming.rag.answering.api.AnsweringPermissions;
import com.liming.rag.identity.api.PermissionCatalog;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/** 把 {@link AnsweringPermissions} 交给 identity 汇总（#136）。 */
@Component
class AnsweringPermissionCatalog implements PermissionCatalog {

    @Override
    public List<Section> sections() {
        return AnsweringPermissions.SECTIONS;
    }

    @Override
    public Set<String> adminDefaults() {
        return AnsweringPermissions.ADMIN_DEFAULTS;
    }
}
