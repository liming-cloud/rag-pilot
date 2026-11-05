package com.liming.rag.cache;

import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 取一组键值存储。同名的共用同一份数据，各上下文的名字不要重复。 */
public interface KeyValueStores {

    /** @param name 小写字母、数字和连字符，作为键的一段 */
    KeyValueStore store(String name);

    /** 存在内存里的实现，和没有 Redis 时的生产实现相同，用于单元测试；过期按 {@code clock} 计算。 */
    static KeyValueStores inMemory(Clock clock) {
        Map<String, KeyValueStore> stores = new ConcurrentHashMap<>();
        return name -> stores.computeIfAbsent(name, n -> new LocalKeyValueStore(clock));
    }
}
