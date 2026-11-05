package com.liming.rag.settings.application.model;

import com.liming.rag.ai.ModelUsage;
import java.util.function.Supplier;

/** 单元测试用：不缓存，每次都读。缓存本身由 infrastructure 的测试覆盖。 */
final class PassThroughModelRouteCache implements ModelRouteCache {

    @Override
    public SavedRoute get(ModelUsage usage, Supplier<SavedRoute> loader) {
        return loader.get();
    }

    @Override
    public void forgetAll() {
    }
}
