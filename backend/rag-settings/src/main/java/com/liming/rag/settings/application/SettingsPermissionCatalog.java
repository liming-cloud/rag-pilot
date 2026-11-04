package com.liming.rag.settings.application;

import com.liming.rag.identity.api.PermissionCatalog;
import com.liming.rag.settings.api.SettingsPermissions;
import java.util.List;
import org.springframework.stereotype.Component;

/** 把 {@link SettingsPermissions} 交给 identity 汇总（#136）。 */
@Component
class SettingsPermissionCatalog implements PermissionCatalog {

    @Override
    public List<Section> sections() {
        return SettingsPermissions.SECTIONS;
    }
}
