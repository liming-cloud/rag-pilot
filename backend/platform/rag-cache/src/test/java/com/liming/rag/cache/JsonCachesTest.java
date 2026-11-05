package com.liming.rag.cache;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
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

    private static final CacheSpec<Access> SPEC = new CacheSpec<>("access", Access.class, Duration.ofMinutes(10));

    private final MemoryStore store = new MemoryStore();
    private final MutableClock clock = new MutableClock();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final JsonCaches caches = new JsonCaches(store, JsonMapper.builder().build(),
            new CacheProperties("rag:cache", Duration.ofSeconds(30)), clock, meters);
    private final TypedCache<Access> cache = caches.cache(SPEC);
    private final AtomicInteger loads = new AtomicInteger();

    @AfterEach
    void endTransaction() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
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
        assertThat(store.values).isEmpty();
    }

    @Test
    void evictOutsideTransactionIsImmediate() {
        cache.get("1", () -> load("kb:view"));
        cache.evict("1");

        assertThat(store.values).isEmpty();
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
        var other = caches.cache(new CacheSpec<>("settings", Access.class, Duration.ofMinutes(10)));
        cache.get("1", () -> load("a"));
        cache.get("2", () -> load("b"));
        other.get("all", () -> load("c"));

        cache.clear();

        assertThat(store.values).containsOnlyKeys("rag:cache:settings:all");
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
    void specRejectsBadNamesAndTtl() {
        org.assertj.core.api.Assertions.assertThatIllegalArgumentException()
                .isThrownBy(() -> new CacheSpec<>("Access:*", Access.class, Duration.ofMinutes(1)));
        org.assertj.core.api.Assertions.assertThatIllegalArgumentException()
                .isThrownBy(() -> new CacheSpec<>("access", Access.class, Duration.ZERO));
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

    private static final class MemoryStore implements CacheStore {

        final Map<String, String> values = new ConcurrentHashMap<>();
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
            return values.get(key);
        }

        @Override
        public void set(String key, String value, Duration ttl) {
            touch();
            values.put(key, value);
            ttls.put(key, ttl);
        }

        @Override
        public void delete(String key) {
            touch();
            values.remove(key);
        }

        @Override
        public void deleteByPrefix(String prefix) {
            touch();
            values.keySet().removeIf(key -> key.startsWith(prefix));
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
