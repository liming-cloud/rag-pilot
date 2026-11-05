package com.liming.rag.identity.application.role;

import com.liming.rag.identity.api.IdentityPermissions;
import com.liming.rag.identity.api.PermissionCatalog;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/** 把 {@link IdentityPermissions} 交给 identity 汇总（#136）。 */
@Component
public class IdentityPermissionCatalog implements PermissionCatalog {

    @Override
    public List<Section> sections() {
        return IdentityPermissions.SECTIONS;
    }

    @Override
    public Set<String> adminDefaults() {
        return IdentityPermissions.ADMIN_DEFAULTS;
    }

    @Override
    public Set<String> staffDefaults() {
        return IdentityPermissions.STAFF_DEFAULTS;
    }
}
