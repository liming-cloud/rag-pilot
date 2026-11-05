package com.liming.rag.settings.application.system;

import com.liming.rag.settings.application.model.Overview;
import com.liming.rag.settings.api.IndustryPack;
import java.util.List;

/** 系统设置页的全部内容。 */
public record SettingsOverview(IndustryPack pack, String statsZone, List<SettingItem> items) {
}
