package com.liming.rag.cache;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

/** RedissonClient 由 rag-app 带上的 redisson-spring-boot-starter 按 {@code spring.data.redis.*} 创建，会话也用它。 */
@Configuration(proxyBeanMethods = false)
class CacheConfiguration {

    @Bean
    Caches caches(RedissonClient redis, ObjectProvider<JsonMapper> json, CacheProperties properties, Clock clock,
            ObjectProvider<MeterRegistry> meters) {
        return new JsonCaches(new RedisCacheStore(redis), json.getIfAvailable(() -> JsonMapper.builder().build()),
                properties, clock, meters.getIfAvailable());
    }
}
