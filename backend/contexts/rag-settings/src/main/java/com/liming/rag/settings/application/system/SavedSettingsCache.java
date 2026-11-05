package com.liming.rag.settings.application.system;

import java.util.List;
import java.util.function.Supplier;

/**
 * 界面保存过的系统设置的缓存（D-053、D-054），整份一条。怎么缓存、存多久由实现决定；保存或恢复后由用例清除，在事务里
 * 清除的等提交后才生效。
 */
public interface SavedSettingsCache {

    /** 有缓存时直接返回，没有时调用 {@code loader} 并缓存结果。 */
    List<SystemSettingsStore.Saved> get(Supplier<List<SystemSettingsStore.Saved>> loader);

    void forget();
}
