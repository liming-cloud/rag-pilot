package com.liming.rag.cache.kv;

import com.liming.rag.cache.KeyValueStores;
import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 键值存储的装配（D-054）：有 Redisson 客户端时放进 Redis，没有时在进程内。上下文只看到 {@link KeyValueStores}。 */
@Configuration(proxyBeanMethods = false)
class KeyValueConfiguration {

    private static final Pattern STORE_NAME = Pattern.compile("[a-z][a-z0-9-]{0,39}");
    /** 键值存储在 Redis 里的前缀；和缓存的前缀分开，缓存恢复时清的是 {@code rag.cache.prefix} 下的键，不会清掉会话。 */
    private static final String STORE_PREFIX = "rag:store:";

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
}
