package com.liming.rag.cache;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

/**
 * 按 {@code rag.cache.redis.enabled} 装配（D-054）：打开时自己按 {@code rag.cache.redis.*} 建 Redisson 客户端，缓存有二级、
 * 键值存储放进 Redis；关着时（默认）缓存只有一级、键值存储在进程内，不连 Redis。上下文只看到接口，不知道是哪一种。
 */
@Configuration(proxyBeanMethods = false)
class CacheConfiguration {

    private static final Pattern STORE_NAME = Pattern.compile("[a-z][a-z0-9-]{0,39}");
    /** 键值存储在 Redis 里的前缀；和缓存的前缀分开，缓存恢复时清的是 {@code rag.cache.prefix} 下的键，不会清掉会话。 */
    private static final String STORE_PREFIX = "rag:store:";

    @Bean
    Caches caches(ObjectProvider<RedissonClient> redis, ObjectProvider<JsonMapper> json, CacheProperties properties,
            Clock clock, ObjectProvider<MeterRegistry> meters) {
        var client = redis.getIfAvailable();
        return new JsonCaches(client == null ? null : new RedisCacheStore(client),
                json.getIfAvailable(() -> JsonMapper.builder().build()), properties, clock, meters.getIfAvailable());
    }

    @Bean
    KeyValueStores keyValueStores(ObjectProvider<RedissonClient> redis, Clock clock) {
        var client = redis.getIfAvailable();
        Map<String, KeyValueStore> stores = new ConcurrentHashMap<>();
        return name -> {
            if (!STORE_NAME.matcher(name).matches()) {
                throw new IllegalArgumentException("存储名只能用小写字母、数字和连字符，以字母开头：" + name);
            }
            return stores.computeIfAbsent(name, n -> client == null
                    ? new LocalKeyValueStore(clock)
                    : new RedisKeyValueStore(client, STORE_PREFIX + n + ":"));
        };
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnBooleanProperty("rag.cache.redis.enabled")
    static class RedisConfiguration {

        @Bean(destroyMethod = "shutdown")
        RedissonClient redissonClient(CacheProperties properties) {
            var redis = properties.redis();
            var config = new Config();
            config.useSingleServer()
                    .setAddress("redis://" + redis.host() + ":" + redis.port())
                    .setPassword(redis.password() == null || redis.password().isBlank() ? null : redis.password())
                    .setTimeout((int) redis.timeout().toMillis())
                    .setConnectTimeout((int) redis.connectTimeout().toMillis());
            return Redisson.create(config);
        }
    }
}
