package com.liming.rag.cache.typed;

import com.liming.rag.cache.Caches;
import com.liming.rag.cache.common.CacheProperties;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

/** 业务缓存的装配（D-054）：有 Redisson 客户端时缓存有二级，没有时只有一级。上下文只看到 {@link Caches}。 */
@Configuration(proxyBeanMethods = false)
class TypedCacheConfiguration {

    @Bean
    Caches caches(ObjectProvider<RedissonClient> redis, ObjectProvider<JsonMapper> json, CacheProperties properties,
            Clock clock, ObjectProvider<MeterRegistry> meters) {
        var client = redis.getIfAvailable();
        return new JsonCaches(client == null ? null : new RedisCacheStore(client),
                json.getIfAvailable(() -> JsonMapper.builder().build()), properties, clock, meters.getIfAvailable());
    }
}
