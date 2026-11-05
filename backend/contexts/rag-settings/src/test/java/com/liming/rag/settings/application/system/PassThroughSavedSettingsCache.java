package com.liming.rag.settings.application.system;

import java.util.List;
import java.util.function.Supplier;

/** 单元测试用：不缓存，每次都读。缓存本身由 infrastructure 的测试覆盖。 */
final class PassThroughSavedSettingsCache implements SavedSettingsCache {

    @Override
    public List<SystemSettingsStore.Saved> get(Supplier<List<SystemSettingsStore.Saved>> loader) {
        return loader.get();
    }

    @Override
    public void forget() {
    }
}
