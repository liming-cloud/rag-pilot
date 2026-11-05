package com.liming.rag.identity.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;

import cn.dev33.satoken.context.mock.SaTokenContextMockUtil;
import cn.dev33.satoken.dao.SaTokenDao;
import com.liming.rag.cache.KeyValueStore;
import com.liming.rag.cache.KeyValueStores;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class SaTokenStoreDaoTest {

    private final KeyValueStore backing = KeyValueStores.inMemory(Clock.systemUTC()).store("sessions");
    private final AtomicInteger reads = new AtomicInteger();
    private final SaTokenStoreDao dao = new SaTokenStoreDao(name -> new CountingStore());

    @Test
    void repeatedReadsInOneRequestHitTheStoreOnce() {
        dao.set("Authorization:login:token:abc", "1", 1800);

        SaTokenContextMockUtil.setMockContext(() -> {
            assertThat(dao.get("Authorization:login:token:abc")).isEqualTo("1");
            assertThat(dao.get("Authorization:login:token:abc")).isEqualTo("1");
            assertThat(dao.get("missing")).isNull();
            assertThat(dao.get("missing")).isNull();
        });

        assertThat(reads).hasValue(2);
    }

    @Test
    void writesInTheRequestAreSeenByLaterReads() {
        SaTokenContextMockUtil.setMockContext(() -> {
            assertThat(dao.get("k")).isNull();
            dao.set("k", "v1", 1800);
            assertThat(dao.get("k")).isEqualTo("v1");
            dao.update("k", "v2");
            assertThat(dao.get("k")).isEqualTo("v2");
            dao.delete("k");
            assertThat(dao.get("k")).isNull();
        });
        assertThat(backing.get("k")).isNull();
    }

    @Test
    void outsideARequestEveryReadGoesToTheStore() {
        dao.set("k", "v", SaTokenDao.NEVER_EXPIRE);

        dao.get("k");
        dao.get("k");

        assertThat(reads).hasValue(2);
        assertThat(dao.getTimeout("k")).isEqualTo(SaTokenDao.NEVER_EXPIRE);
    }

    @Test
    void timeoutsFollowSaTokenConventions() {
        dao.set("ignored", "v", 0);
        assertThat(dao.getTimeout("ignored")).isEqualTo(SaTokenDao.NOT_VALUE_EXPIRE);

        dao.set("k", "v", 1800);
        dao.update("k", "v2");
        assertThat(dao.getTimeout("k")).as("update 保留剩余过期时间").isBetween(1798L, 1800L);
        dao.updateTimeout("k", 60);
        assertThat(dao.getTimeout("k")).isBetween(58L, 60L);
    }

    @Test
    void searchDataFiltersByKeyword() {
        dao.set("Authorization:login:token:aa1", "1", 1800);
        dao.set("Authorization:login:token:bb2", "2", 1800);

        assertThat(dao.searchData("Authorization:login:token:", "aa", 0, -1, true))
                .containsExactly("Authorization:login:token:aa1");
    }

    /** 记下读的次数，其他操作转给内存存储。 */
    private final class CountingStore implements KeyValueStore {

        @Override
        public @Nullable String get(String key) {
            reads.incrementAndGet();
            return backing.get(key);
        }

        @Override
        public void set(String key, String value, @Nullable Duration ttl) {
            backing.set(key, value, ttl);
        }

        @Override
        public boolean update(String key, String value) {
            return backing.update(key, value);
        }

        @Override
        public void delete(String key) {
            backing.delete(key);
        }

        @Override
        public long remaining(String key) {
            return backing.remaining(key);
        }

        @Override
        public void expire(String key, @Nullable Duration ttl) {
            backing.expire(key, ttl);
        }

        @Override
        public List<String> keys(String prefix) {
            return backing.keys(prefix);
        }
    }
}
