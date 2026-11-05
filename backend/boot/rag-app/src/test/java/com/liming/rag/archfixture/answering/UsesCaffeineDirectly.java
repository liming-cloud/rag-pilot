package com.liming.rag.archfixture.answering;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

/** 违规样例（D-054）：上下文自己建进程内缓存，应经 rag-cache。 */
public class UsesCaffeineDirectly {

    private final Cache<String, String> cache = Caffeine.newBuilder().maximumSize(10).build();

    String read(String key) {
        return cache.get(key, k -> k);
    }
}
