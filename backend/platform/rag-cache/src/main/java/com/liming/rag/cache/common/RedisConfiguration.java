package com.liming.rag.cache.common;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 按 {@code rag.cache.redis.enabled} 装配（D-054）：打开时自己按 {@code rag.cache.redis.*} 建 Redisson 客户端，关着时不连 Redis。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnBooleanProperty("rag.cache.redis.enabled")
class RedisConfiguration {

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
