package com.liming.rag.cache.kv;

import com.liming.rag.cache.KeyValueStores;
import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** {@link KeyValueStores#inMemory} 的实现入口：根包接口不能直接访问包内私有的 {@code LocalKeyValueStore}。 */
public final class InMemoryKeyValueStores {

    private InMemoryKeyValueStores() {
    }

    public static KeyValueStores create(Clock clock) {
        Map<String, KeyValueStore> stores = new ConcurrentHashMap<>();
        return name -> stores.computeIfAbsent(name, n -> new LocalKeyValueStore(clock));
    }
}
