package com.liming.rag.data.application.grant;

import com.liming.rag.data.api.DataPermissions;
import com.liming.rag.identity.api.PermissionCatalog;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/** 把 {@link DataPermissions} 交给 identity 汇总。 */
@Component
class DataPermissionCatalog implements PermissionCatalog {

    @Override
    public List<Section> sections() {
        return DataPermissions.SECTIONS;
    }

    @Override
    public Set<String> adminDefaults() {
        return DataPermissions.ADMIN_DEFAULTS;
    }
}
