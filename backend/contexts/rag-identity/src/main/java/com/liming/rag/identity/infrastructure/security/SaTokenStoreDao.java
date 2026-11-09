package com.liming.rag.identity.infrastructure.security;

import cn.dev33.satoken.SaManager;
import cn.dev33.satoken.context.SaHolder;
import cn.dev33.satoken.dao.SaTokenDao;
import cn.dev33.satoken.dao.auto.SaTokenDaoByObjectFollowString;
import cn.dev33.satoken.util.SaFoxUtil;
import com.liming.rag.cache.kv.KeyValueStore;
import com.liming.rag.cache.KeyValueStores;
import java.time.Duration;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Sa-Token 的会话存储，经平台组件 rag-cache 的键值存储（D-054）：配了 Redis 时会话在 Redis 里，应用重启后不用重新登录；
 * 没配时在进程内。对象按 Sa-Token 的 JSON 序列化成字符串再存。
 *
 * <p>一个请求里 Sa-Token 会多次读同一个键（令牌、会话），同一请求内只读一次存储：读到的值记在请求的 {@code SaStorage} 里，
 * 本请求的写入、删除同步更新这份记录。不在请求里（如启动时重置管理员密码）时直接读写存储。
 */
@Component
class SaTokenStoreDao implements SaTokenDaoByObjectFollowString {

    private static final String MEMO = "rag.session-store:";

    /** 请求内记下的值；{@code value} 为 null 表示存储里没有。 */
    private record Memo(@Nullable String value) {
    }

    private final KeyValueStore store;

    SaTokenStoreDao(KeyValueStores stores) {
        this.store = stores.store("sessions");
    }

    @Override
    public @Nullable String get(String key) {
        var memo = recalled(key);
        if (memo != null) {
            return memo.value();
        }
        var value = store.get(key);
        remember(key, value);
        return value;
    }

    @Override
    public void set(String key, String value, long timeout) {
        if (timeout == 0 || timeout <= SaTokenDao.NOT_VALUE_EXPIRE) {
            return;
        }
        store.set(key, value, ttl(timeout));
        remember(key, value);
    }

    @Override
    public void update(String key, String value) {
        if (store.update(key, value)) {
            remember(key, value);
        }
    }

    @Override
    public void delete(String key) {
        store.delete(key);
        remember(key, null);
    }

    @Override
    public long getTimeout(String key) {
        var remaining = store.remaining(key);
        return remaining < 0 ? remaining : remaining / 1000;
    }

    @Override
    public void updateTimeout(String key, long timeout) {
        store.expire(key, ttl(timeout));
    }

    @Override
    public List<String> searchData(String prefix, String keyword, int start, int size, boolean sortType) {
        var keys = store.keys(prefix).stream().filter(key -> key.substring(prefix.length()).contains(keyword)).toList();
        return SaFoxUtil.searchList(keys, start, size, sortType);
    }

    private static @Nullable Duration ttl(long timeout) {
        return timeout == SaTokenDao.NEVER_EXPIRE ? null : Duration.ofSeconds(timeout);
    }

    private static @Nullable Memo recalled(String key) {
        return inRequest() && SaHolder.getStorage().get(MEMO + key) instanceof Memo memo ? memo : null;
    }

    private static void remember(String key, @Nullable String value) {
        if (inRequest()) {
            SaHolder.getStorage().set(MEMO + key, new Memo(value));
        }
    }

    private static boolean inRequest() {
        return SaManager.getSaTokenContext().isValid();
    }
}
