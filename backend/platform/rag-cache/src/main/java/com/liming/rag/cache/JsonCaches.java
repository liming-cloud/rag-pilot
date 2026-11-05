package com.liming.rag.cache;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * 缓存的全部机制（D-053）：键是 {@code <前缀>:<缓存名>:<键>}，值存 JSON。
 *
 * <p>缓存不是硬依赖：存取出错时记一行 {@code event=cache_failure}，读直接走加载，之后 {@code retryAfter} 内不再访问
 * 存储；出错期间的清除可能没做成，所以恢复后第一次访问前先清掉本前缀下的全部缓存，再照常使用。存的 JSON 读不回来
 * （如升级后 record 改了字段）按没有缓存处理，加载后覆盖。
 *
 * <p>一致性：清除在事务提交后做；清除前先把全局代数（{@code <前缀>@generation}）加一，读不到时先记下代数再加载，写回时
 * 代数没变才写（Redis 里用 Lua 原子比较）。所以“加载时读到旧数据、清除之后才写回”的写入会被拒绝，不会把刚收回的权限
 * 又放回缓存。代数是全局一个：清除很少（管理操作），代价只是同时在加载的其他键这次不写回。
 *
 * <p>指标 {@code rag.cache.gets}，标签 {@code cache}（缓存名）和 {@code result}（hit、miss、error）；
 * {@code rag.cache.rejected_writes}：因为期间有清除而没有写回的次数。
 */
final class JsonCaches implements Caches {

    private static final Logger log = LoggerFactory.getLogger(JsonCaches.class);

    private final CacheStore store;
    private final JsonMapper json;
    private final CacheProperties properties;
    private final Clock clock;
    private final @Nullable MeterRegistry meters;
    private final String generationKey;

    /** 在这个时刻（毫秒）之前不访问存储。 */
    private volatile long skipUntil;
    /**
     * 第一次访问前、出过错恢复后，先清掉本前缀下的全部缓存。启动时也要清：升级时的迁移、停机期间的改动都可能让 Redis
     * 里留着的值过时。
     */
    private volatile boolean flushPending = true;

    JsonCaches(CacheStore store, JsonMapper json, CacheProperties properties, Clock clock,
            @Nullable MeterRegistry meters) {
        this.store = store;
        this.json = json;
        this.properties = properties;
        this.clock = clock;
        this.meters = meters;
        this.generationKey = properties.prefix() + "@generation";
    }

    @Override
    public <V> TypedCache<V> cache(CacheSpec<V> spec) {
        return new Typed<>(spec);
    }

    private final class Typed<V> implements TypedCache<V> {

        private final CacheSpec<V> spec;
        private final String namespace;

        Typed(CacheSpec<V> spec) {
            this.spec = spec;
            this.namespace = properties.prefix() + ":" + spec.name() + ":";
        }

        @Override
        public @Nullable V get(String key, Supplier<? extends @Nullable V> loader) {
            var fullKey = namespace + key;
            if (!usable()) {
                count("error");
                return loader.get();
            }
            String cached;
            String generation;
            try {
                cached = store.get(fullKey);
                // 加载之前记下代数：加载期间有清除时，写回会被拒绝。
                generation = cached == null ? store.generation(generationKey) : "";
            } catch (RuntimeException e) {
                failed("get", e);
                count("error");
                return loader.get();
            }
            if (cached != null) {
                var value = parse(cached);
                if (value != null) {
                    count("hit");
                    return value;
                }
                try {
                    generation = store.generation(generationKey);
                } catch (RuntimeException e) {
                    failed("get", e);
                    count("error");
                    return loader.get();
                }
            }
            count("miss");
            var loaded = loader.get();
            if (loaded != null) {
                write(fullKey, loaded, generation);
            }
            return loaded;
        }

        @Override
        public void evict(String key) {
            afterCommit("evict", () -> store.bumpAndDelete(generationKey, namespace + key));
        }

        @Override
        public void clear() {
            afterCommit("clear", () -> store.bumpAndDeleteByPrefix(generationKey, namespace));
        }

        private @Nullable V parse(String cached) {
            try {
                return json.readValue(cached, spec.type());
            } catch (JacksonException e) {
                log.info("缓存 {} 里的值读不回来（可能是升级后结构变了），重新加载：{}", spec.name(), e.getClass().getSimpleName());
                return null;
            }
        }

        private void write(String fullKey, V value, String generation) {
            try {
                if (!store.setIfGeneration(fullKey, json.writeValueAsString(value), spec.ttl(), generationKey, generation)
                        && meters != null) {
                    Counter.builder("rag.cache.rejected_writes").tag("cache", spec.name()).register(meters).increment();
                }
            } catch (RuntimeException e) {
                failed("set", e);
            }
        }

        private void count(String result) {
            if (meters != null) {
                Counter.builder("rag.cache.gets").tag("cache", spec.name()).tag("result", result)
                        .register(meters).increment();
            }
        }

        private void failed(String operation, RuntimeException e) {
            JsonCaches.this.failed(spec.name(), operation, e);
        }

        private void afterCommit(String operation, Runnable action) {
            Runnable guarded = () -> {
                if (!usable()) {
                    // 存储暂时不可用：这次清除做不成，恢复后会清掉全部缓存。
                    flushPending = true;
                    return;
                }
                try {
                    action.run();
                } catch (RuntimeException e) {
                    failed(operation, e);
                }
            };
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        guarded.run();
                    }
                });
            } else {
                guarded.run();
            }
        }
    }

    /** 现在能不能访问存储；出过错又过了等待时间时，先清掉全部缓存，清不掉仍算不能用。 */
    private boolean usable() {
        if (clock.millis() < skipUntil) {
            return false;
        }
        if (flushPending) {
            try {
                store.bumpAndDeleteByPrefix(generationKey, properties.prefix() + ":");
                flushPending = false;
                log.info("event=cache_recovered prefix={}", properties.prefix());
            } catch (RuntimeException e) {
                failed("*", "flush", e);
                return false;
            }
        }
        return true;
    }

    private void failed(String cache, String operation, RuntimeException e) {
        flushPending = true;
        skipUntil = clock.millis() + properties.retryAfter().toMillis();
        log.warn("event=cache_failure cache={} op={} error={} retry_after={}s", cache, operation,
                e.getClass().getSimpleName(), properties.retryAfter().toSeconds());
    }
}
