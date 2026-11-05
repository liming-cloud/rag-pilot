package com.liming.rag.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * 缓存的全部机制（D-053、D-054）。读的顺序是一级（进程内 Caffeine，存对象）→ 二级（Redis，键
 * {@code <前缀>:<缓存名>:<键>}，值存 JSON）→ 加载；每种缓存用哪几层由 {@link CacheProperties#policy} 决定，没有 Redis 时
 * 只有一级。只按单个应用实例设计：一级缓存靠本进程的清除保持一致，不做跨实例通知。
 *
 * <p>二级缓存不是硬依赖：存取出错时记一行 {@code event=cache_failure}，跳过二级，之后 {@code retryAfter} 内不再访问
 * Redis；出错期间的清除可能没做成，所以恢复后第一次访问前先清掉本前缀下的全部键，启动后第一次访问前也清一次。存的 JSON
 * 读不回来（如升级后 record 改了字段）按没有缓存处理，加载后覆盖。一级缓存在 Redis 出错时照常使用。
 *
 * <p>一致性：清除在事务提交后做，先清二级再清一级。防止加载期间读到的旧值在清除之后才写回：一级用进程内的失效计数，加载前
 * 记下，填入时在键锁里比较，变了就不填；二级用全局代数（{@code <前缀>@generation}），写回时在 Redis 里用 Lua 原子比较，
 * 变了就不写。
 * 两个计数都是全局一个：清除很少（管理操作），代价只是同时在加载的其他键这次不缓存。
 *
 * <p>指标 {@code rag.cache.gets}，标签 {@code cache} 和 {@code result}（local_hit、hit、miss、error）；
 * {@code rag.cache.rejected_writes}，标签 {@code tier}（local、remote）：因为期间有清除而没有缓存的次数。
 */
final class JsonCaches implements Caches {

    private static final Logger log = LoggerFactory.getLogger(JsonCaches.class);

    private final @Nullable CacheStore remote;
    private final JsonMapper json;
    private final CacheProperties properties;
    private final Clock clock;
    private final @Nullable MeterRegistry meters;
    private final String generationKey;
    /** 一级缓存的失效计数，每次清除加一。 */
    private final AtomicLong localGeneration = new AtomicLong();

    /** 在这个时刻（毫秒）之前不访问 Redis。 */
    private volatile long skipUntil;
    /**
     * 第一次访问前、出过错恢复后，先清掉 Redis 里本前缀下的全部缓存。启动时也要清：升级时的迁移、停机期间的改动都可能让
     * Redis 里留着的值过时。
     */
    private volatile boolean flushPending = true;

    /** @param remote 二级缓存的存储，不用 Redis 时为 null */
    JsonCaches(@Nullable CacheStore remote, JsonMapper json, CacheProperties properties, Clock clock,
            @Nullable MeterRegistry meters) {
        this.remote = remote;
        this.json = json;
        this.properties = properties;
        this.clock = clock;
        this.meters = meters;
        this.generationKey = properties.prefix() + "@generation";
    }

    /** 同名的缓存只建一个，一级缓存和清除才是同一份。 */
    private final Map<String, Typed<?>> typed = new ConcurrentHashMap<>();

    @Override
    @SuppressWarnings("unchecked") // 名字相同时下面检查了值的类型
    public <V> TypedCache<V> cache(CacheSpec<V> spec) {
        var cache = typed.computeIfAbsent(spec.name(), name -> new Typed<>(spec, properties.policy(name)));
        if (!cache.spec.type().equals(spec.type())) {
            throw new IllegalArgumentException("缓存 " + spec.name() + " 已经用于 " + cache.spec.type().getName());
        }
        return (TypedCache<V>) cache;
    }

    private final class Typed<V> implements TypedCache<V> {

        private final CacheSpec<V> spec;
        private final CacheProperties.Policy policy;
        private final String namespace;
        private final @Nullable Cache<String, V> local;

        Typed(CacheSpec<V> spec, CacheProperties.Policy policy) {
            this.spec = spec;
            this.policy = policy;
            this.namespace = properties.prefix() + ":" + spec.name() + ":";
            this.local = policy.local()
                    ? Caffeine.newBuilder().maximumSize(policy.maxSize()).expireAfterWrite(policy.localTtl()).build()
                    : null;
        }

        @Override
        public @Nullable V get(String key, Supplier<? extends @Nullable V> loader) {
            if (local == null) {
                return getRemote(key, loader);
            }
            var cached = local.getIfPresent(key);
            if (cached != null) {
                count("local_hit");
                return cached;
            }
            // 加载之前记下计数：加载期间有清除时不填一级。比较和填入在同一个键锁里做：清除先加计数再删键，删键要等这把锁，
            // 所以不会出现“先填进去、再发现过时去删”之间被别的请求读到旧值。
            var generation = localGeneration.get();
            var loaded = getRemote(key, loader);
            if (loaded != null) {
                var stored = local.asMap().compute(key,
                        (k, existing) -> localGeneration.get() == generation ? loaded : existing);
                if (stored != loaded) {
                    rejected("local");
                }
            }
            return loaded;
        }

        private void clearRemote(String operation, Consumer<CacheStore> clear) {
            var store = remote;
            if (store == null || !policy.remote()) {
                return;
            }
            if (!usable(store)) {
                // Redis 暂时不可用：这次清除做不成，恢复后会清掉全部缓存。
                flushPending = true;
                return;
            }
            try {
                clear.accept(store);
            } catch (RuntimeException e) {
                failed(operation, e);
            }
        }

        private @Nullable V countedLoad(Supplier<? extends @Nullable V> loader) {
            count("miss");
            return loader.get();
        }

        private @Nullable V getRemote(String key, Supplier<? extends @Nullable V> loader) {
            var store = remote;
            if (store == null || !policy.remote()) {
                return countedLoad(loader);
            }
            var fullKey = namespace + key;
            if (!usable(store)) {
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
                write(store, fullKey, loaded, generation);
            }
            return loaded;
        }

        @Override
        public void evict(String key) {
            afterCommit("evict", () -> {
                if (local != null) {
                    localGeneration.incrementAndGet();
                    local.invalidate(key);
                }
            }, store -> store.bumpAndDelete(generationKey, namespace + key));
        }

        @Override
        public void clear() {
            afterCommit("clear", () -> {
                if (local != null) {
                    localGeneration.incrementAndGet();
                    local.invalidateAll();
                }
            }, store -> store.bumpAndDeleteByPrefix(generationKey, namespace));
        }

        private @Nullable V parse(String cached) {
            try {
                return json.readValue(cached, spec.type());
            } catch (JacksonException e) {
                log.info("缓存 {} 里的值读不回来（可能是升级后结构变了），重新加载：{}", spec.name(), e.getClass().getSimpleName());
                return null;
            }
        }

        private void write(CacheStore store, String fullKey, V value, String generation) {
            try {
                if (!store.setIfGeneration(fullKey, json.writeValueAsString(value), policy.remoteTtl(), generationKey,
                        generation)) {
                    rejected("remote");
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

        private void rejected(String tier) {
            if (meters != null) {
                Counter.builder("rag.cache.rejected_writes").tag("cache", spec.name()).tag("tier", tier)
                        .register(meters).increment();
            }
        }

        private void failed(String operation, RuntimeException e) {
            JsonCaches.this.failed(spec.name(), operation, e);
        }

        /**
         * 提交后（没有事务时立即）先清二级、再清一级：反过来的话，一级清完之后才开始读的请求可能从 Redis 读到还没删的旧值
         * 填回一级。二级出错只记日志，恢复后会清掉全部；一级总是清。
         */
        private void afterCommit(String operation, Runnable clearLocal, Consumer<CacheStore> clearRemote) {
            Runnable guarded = () -> {
                try {
                    clearRemote(operation, clearRemote);
                } finally {
                    clearLocal.run();
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

    /** 现在能不能访问 Redis；出过错又过了等待时间时，先清掉全部缓存，清不掉仍算不能用。 */
    private boolean usable(CacheStore store) {
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
