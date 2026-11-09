package com.liming.rag.cache.typed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import com.liming.rag.cache.common.CacheProperties;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

class JsonCachesTest {

    record Access(long accountId, List<String> permissions) {
    }

    private static final CacheSpec<Access> SPEC = new CacheSpec<>("access", Access.class);
    private static final CacheProperties.Redis REDIS =
            new CacheProperties.Redis(true, "localhost", 6379, null, Duration.ofMillis(500), Duration.ofSeconds(2));

    private final MemoryStore store = new MemoryStore();
    private final MutableClock clock = new MutableClock();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    /** 这一组用例测二级缓存的机制，默认关掉一级。 */
    private final JsonCaches caches = new JsonCaches(store, JsonMapper.builder().build(), properties(false, null), clock,
            meters);
    private final TypedCache<Access> cache = caches.cache(SPEC);
    private final AtomicInteger loads = new AtomicInteger();

    @AfterEach
    void endTransaction() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private static CacheProperties properties(boolean local,
            @Nullable Map<String, CacheProperties.Tiers> caches) {
        return new CacheProperties("rag:cache", Duration.ofSeconds(30), REDIS,
                new CacheProperties.Tiers(new CacheProperties.Local(local, 100L, Duration.ofMinutes(5)),
                        new CacheProperties.Remote(true, Duration.ofMinutes(10))),
                caches);
    }

    private Access load(String... permissions) {
        loads.incrementAndGet();
        return new Access(1, List.of(permissions));
    }

    @Test
    void loadsOnMissAndServesFromStoreAfterwards() {
        assertThat(cache.get("1", () -> load("kb:view"))).isEqualTo(new Access(1, List.of("kb:view")));
        assertThat(cache.get("1", () -> load("changed"))).isEqualTo(new Access(1, List.of("kb:view")));

        assertThat(loads).hasValue(1);
        assertThat(store.values).containsKey("rag:cache:access:1");
        assertThat(store.ttls.get("rag:cache:access:1")).isEqualTo(Duration.ofMinutes(10));
        assertThat(count("hit")).isEqualTo(1);
        assertThat(count("miss")).isEqualTo(1);
    }

    @Test
    void nullIsNotCached() {
        assertThat(cache.get("9", () -> null)).isNull();
        assertThat(cacheKeys()).isEmpty();
    }

    @Test
    void evictOutsideTransactionIsImmediate() {
        cache.get("1", () -> load("kb:view"));
        cache.evict("1");

        assertThat(cacheKeys()).isEmpty();
        assertThat(cache.get("1", () -> load("user:view"))).isEqualTo(new Access(1, List.of("user:view")));
    }

    @Test
    void evictInsideTransactionWaitsForCommitAndIsSkippedOnRollback() {
        cache.get("1", () -> load("kb:view"));
        TransactionSynchronizationManager.initSynchronization();

        cache.evict("1");
        assertThat(store.values).containsKey("rag:cache:access:1");
        rollback();
        assertThat(store.values).containsKey("rag:cache:access:1");

        TransactionSynchronizationManager.initSynchronization();
        cache.evict("1");
        commit();
        assertThat(store.values).doesNotContainKey("rag:cache:access:1");
    }

    @Test
    void clearRemovesOnlyThisCache() {
        var other = caches.cache(new CacheSpec<>("settings", Access.class));
        cache.get("1", () -> load("a"));
        cache.get("2", () -> load("b"));
        other.get("all", () -> load("c"));

        cache.clear();

        assertThat(cacheKeys()).containsOnly("rag:cache:settings:all");
    }

    @Test
    void storeFailureFallsBackToLoaderAndSkipsStoreForAWhile() {
        store.failing = true;

        assertThat(cache.get("1", () -> load("kb:view"))).isEqualTo(new Access(1, List.of("kb:view")));
        int callsAfterFailure = store.calls.get();
        assertThat(cache.get("1", () -> load("kb:view"))).isNotNull();

        assertThat(loads).hasValue(2);
        assertThat(store.calls).as("出错后等待期内不再访问存储").hasValue(callsAfterFailure);
        assertThat(count("error")).isEqualTo(2);
    }

    @Test
    void afterRecoveryEverythingIsFlushedBecauseEvictionsMayHaveBeenMissed() {
        cache.get("1", () -> load("kb:view"));
        store.failing = true;
        cache.evict("1");
        store.failing = false;

        // 等待期内：读库，不碰存储里那份过时的值。
        assertThat(cache.get("1", () -> load("revoked"))).isEqualTo(new Access(1, List.of("revoked")));
        clock.advance(Duration.ofSeconds(31));

        assertThat(cache.get("1", () -> load("revoked"))).isEqualTo(new Access(1, List.of("revoked")));
        assertThat(store.values.get("rag:cache:access:1")).contains("revoked");
    }

    @Test
    void valuesLeftFromBeforeStartAreFlushedOnFirstUse() {
        store.values.put("rag:cache:access:1", "{\"accountId\":1,\"permissions\":[\"stale\"]}");
        store.values.put("Authorization:login:token:abc", "1");

        assertThat(cache.get("1", () -> load("kb:view"))).isEqualTo(new Access(1, List.of("kb:view")));
        assertThat(store.values).as("只清缓存前缀，不动会话").containsKey("Authorization:login:token:abc");
    }

    @Test
    void unreadableValueIsReloadedAndOverwritten() {
        cache.get("other", () -> load());
        store.values.put("rag:cache:access:1", "{\"accountId\":\"not-a-number\"");

        assertThat(cache.get("1", () -> load("kb:view"))).isEqualTo(new Access(1, List.of("kb:view")));
        assertThat(store.values.get("rag:cache:access:1")).contains("kb:view");
    }

    @Test
    void specRejectsBadNames() {
        assertThatIllegalArgumentException().isThrownBy(() -> new CacheSpec<>("Access:*", Access.class));
    }

    @Test
    void sameNameWithAnotherTypeIsRejected() {
        assertThat(caches.cache(new CacheSpec<>("access", Access.class))).isSameAs(cache);
        assertThatIllegalArgumentException().isThrownBy(() -> caches.cache(new CacheSpec<>("access", String.class)));
    }

    @Test
    void invalidTierConfigurationFailsAtStartup() {
        assertThatIllegalArgumentException().isThrownBy(() -> properties(true,
                Map.of("access", new CacheProperties.Tiers(new CacheProperties.Local(false, null, null),
                        new CacheProperties.Remote(false, null)))));
        assertThatIllegalArgumentException().isThrownBy(() -> properties(true,
                Map.of("access", new CacheProperties.Tiers(new CacheProperties.Local(null, 0L, null), null))));
        assertThatIllegalArgumentException().isThrownBy(() -> new CacheProperties("rag:cache", Duration.ofSeconds(30),
                REDIS, new CacheProperties.Tiers(new CacheProperties.Local(true, 10L, null), null), null));
    }

    // ---- 一级缓存 ----

    private final JsonCaches tiered = new JsonCaches(store, JsonMapper.builder().build(), properties(true,
            Map.of("vectors", new CacheProperties.Tiers(new CacheProperties.Local(false, null, null), null))), clock,
            meters);
    private final TypedCache<Access> both = tiered.cache(SPEC);

    @Test
    void localHitDoesNotTouchRedis() {
        both.get("1", () -> load("kb:view"));
        int calls = store.calls.get();

        assertThat(both.get("1", () -> load("changed"))).isEqualTo(new Access(1, List.of("kb:view")));
        assertThat(store.calls).hasValue(calls);
        assertThat(loads).hasValue(1);
        assertThat(count("local_hit")).isEqualTo(1);
    }

    @Test
    void evictClearsBothTiers() {
        both.get("1", () -> load("kb:view"));
        both.evict("1");

        assertThat(cacheKeys()).isEmpty();
        assertThat(both.get("1", () -> load("user:view"))).isEqualTo(new Access(1, List.of("user:view")));
    }

    @Test
    void staleLoadIsNotKeptInEitherTierWhenEvictedWhileLoading() {
        both.get("1", () -> {
            var old = load("kb:view");
            both.evict("1");
            return old;
        });

        assertThat(cacheKeys()).doesNotContain("rag:cache:access:1");
        assertThat(both.get("1", () -> load())).as("一级也没留下旧值").isEqualTo(new Access(1, List.of()));
        assertThat(rejected("local")).isEqualTo(1);
    }

    @Test
    void remoteOnlyCacheSkipsLocalTier() {
        var vectors = tiered.cache(new CacheSpec<>("vectors", Access.class));
        vectors.get("q", () -> load("v"));
        int calls = store.calls.get();

        vectors.get("q", () -> load("v"));

        assertThat(store.calls.get()).as("每次都读 Redis").isGreaterThan(calls);
        assertThat(loads).hasValue(1);
    }

    @Test
    void localTierStillServesWhileRedisIsDown() {
        both.get("1", () -> load("kb:view"));
        store.failing = true;

        assertThat(both.get("1", () -> load("changed"))).isEqualTo(new Access(1, List.of("kb:view")));
        both.evict("1");
        assertThat(both.get("1", () -> load("revoked"))).as("Redis 出错时本进程的清除仍然生效")
                .isEqualTo(new Access(1, List.of("revoked")));
    }

    @Test
    void withoutRedisOnlyTheLocalTierIsUsed() {
        var local = new JsonCaches(null, JsonMapper.builder().build(), CacheProperties.localOnly(), clock, meters)
                .cache(SPEC);
        local.get("1", () -> load("kb:view"));

        assertThat(local.get("1", () -> load("changed"))).isEqualTo(new Access(1, List.of("kb:view")));
        local.evict("1");
        assertThat(local.get("1", () -> load("revoked"))).isEqualTo(new Access(1, List.of("revoked")));
        assertThat(store.calls).hasValue(0);
    }

    @Test
    void staleLoadIsNotWrittenBackWhenEvictedWhileLoading() {
        // 读请求加载时读到的是旧权限；加载期间管理员收回权限、提交并清除；旧值不能写回缓存。
        var stale = cache.get("1", () -> {
            var old = load("kb:view");
            cache.evict("1");
            return old;
        });

        assertThat(stale).isEqualTo(new Access(1, List.of("kb:view")));
        assertThat(cacheKeys()).doesNotContain("rag:cache:access:1");
        assertThat(cache.get("1", () -> load())).isEqualTo(new Access(1, List.of()));
        assertThat(rejectedWrites()).isEqualTo(1);
    }

    @Test
    void staleLoadIsNotWrittenBackWhenClearedWhileLoading() {
        cache.get("1", () -> {
            var old = load("kb:view");
            cache.clear();
            return old;
        });

        assertThat(cacheKeys()).doesNotContain("rag:cache:access:1");
    }

    @Test
    void evictionInTransactionRejectsLoadThatStartedBeforeCommit() {
        TransactionSynchronizationManager.initSynchronization();
        cache.evict("1");
        // 另一个请求在提交前开始加载（读到旧数据），提交后才写回。
        var stale = cache.get("1", () -> {
            var old = load("kb:view");
            commit();
            return old;
        });

        assertThat(stale).isNotNull();
        assertThat(cacheKeys()).doesNotContain("rag:cache:access:1");
    }

    private List<String> cacheKeys() {
        return store.values.keySet().stream().filter(key -> key.startsWith("rag:cache:")).toList();
    }

    private double rejectedWrites() {
        return rejected("remote");
    }

    private double rejected(String tier) {
        var counter = meters.find("rag.cache.rejected_writes").tag("cache", "access").tag("tier", tier).counter();
        return counter == null ? 0 : counter.count();
    }

    private double count(String result) {
        var counter = meters.find("rag.cache.gets").tag("cache", "access").tag("result", result).counter();
        return counter == null ? 0 : counter.count();
    }

    private static void commit() {
        var synchronizations = TransactionSynchronizationManager.getSynchronizations();
        TransactionSynchronizationManager.clearSynchronization();
        synchronizations.forEach(TransactionSynchronization::afterCommit);
    }

    private static void rollback() {
        TransactionSynchronizationManager.clearSynchronization();
    }

    /** 内存存储，加上调用计数、记下过期时间和模拟出错。 */
    private static final class MemoryStore extends MemoryCacheStore {

        final Map<String, Duration> ttls = new ConcurrentHashMap<>();
        final AtomicInteger calls = new AtomicInteger();
        volatile boolean failing;

        private void touch() {
            calls.incrementAndGet();
            if (failing) {
                throw new IllegalStateException("Redis 连不上");
            }
        }

        @Override
        public @Nullable String get(String key) {
            touch();
            return super.get(key);
        }

        @Override
        public String generation(String generationKey) {
            touch();
            return super.generation(generationKey);
        }

        @Override
        public boolean setIfGeneration(String key, String value, Duration ttl, String generationKey, String expected) {
            touch();
            var written = super.setIfGeneration(key, value, ttl, generationKey, expected);
            if (written) {
                ttls.put(key, ttl);
            }
            return written;
        }

        @Override
        public void bumpAndDelete(String generationKey, String key) {
            touch();
            super.bumpAndDelete(generationKey, key);
        }

        @Override
        public void bumpAndDeleteByPrefix(String generationKey, String prefix) {
            touch();
            super.bumpAndDeleteByPrefix(generationKey, prefix);
        }
    }

    private static final class MutableClock extends Clock {

        private Instant now = Instant.parse("2026-11-05T00:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
