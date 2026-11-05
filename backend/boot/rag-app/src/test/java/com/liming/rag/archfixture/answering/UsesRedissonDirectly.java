package com.liming.rag.archfixture.answering;

import org.redisson.api.RedissonClient;

/** 违规样例（D-053）：上下文直接用 Redis 客户端，应经 rag-cache 的 {@code Caches}。 */
public class UsesRedissonDirectly {

    Object read(RedissonClient redis, String key) {
        return redis.getBucket(key).get();
    }
}
