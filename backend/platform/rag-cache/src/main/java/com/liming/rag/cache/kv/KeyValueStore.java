package com.liming.rag.cache.kv;

import org.springframework.modulith.NamedInterface;
import java.time.Duration;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * 一组键值数据的存储，存的是权威数据（如登录会话），背后没有数据库可以回退：不加一级缓存、不降级，出错直接抛运行时异常。
 * 没有 Redis 时存在进程内，应用重启后丢失（D-054）。
 */
@NamedInterface("kv")
public interface KeyValueStore {

    /** {@link #remaining} 的返回值：键不存在。 */
    long MISSING = -2;
    /** {@link #remaining} 的返回值：不过期。 */
    long PERSISTENT = -1;

    @Nullable String get(String key);

    /** 写入；{@code ttl} 为 null 时不过期。 */
    void set(String key, String value, @Nullable Duration ttl);

    /** 键存在时改值、保留剩余的过期时间，返回是否改了。 */
    boolean update(String key, String value);

    void delete(String key);

    /** 剩余过期时间（毫秒）；键不存在时 {@link #MISSING}，不过期时 {@link #PERSISTENT}。 */
    long remaining(String key);

    /** 改过期时间；{@code ttl} 为 null 时改为不过期。键不存在时什么也不做。 */
    void expire(String key, @Nullable Duration ttl);

    /** 以 {@code prefix} 开头的全部键。 */
    List<String> keys(String prefix);
}
