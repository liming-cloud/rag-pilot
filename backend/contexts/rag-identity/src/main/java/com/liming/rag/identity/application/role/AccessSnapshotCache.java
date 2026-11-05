package com.liming.rag.identity.application.role;

import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * 账号权限快照的缓存（D-053、D-054）。怎么缓存、存多久由实现决定；何时清除由用例决定，在事务里清除的等提交后才生效，
 * 回滚时不清。
 */
public interface AccessSnapshotCache {

    /** 有缓存时直接返回；没有时调用 {@code loader}，结果为 null（账号不存在）时不缓存。 */
    @Nullable AccessSnapshot get(long accountId, Supplier<@Nullable AccessSnapshot> loader);

    void forget(long accountId);

    void forgetAll();
}
