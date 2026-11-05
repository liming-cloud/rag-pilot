package com.liming.rag.cache;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class LocalKeyValueStoreTest {

    private final MutableClock clock = new MutableClock();
    private final KeyValueStore store = KeyValueStores.inMemory(clock).store("sessions");

    @Test
    void valuesExpireAfterTheirTtl() {
        store.set("token:a", "1", Duration.ofMinutes(30));

        clock.advance(Duration.ofMinutes(29));
        assertThat(store.get("token:a")).isEqualTo("1");
        assertThat(store.remaining("token:a")).isEqualTo(Duration.ofMinutes(1).toMillis());

        clock.advance(Duration.ofMinutes(2));
        assertThat(store.get("token:a")).isNull();
        assertThat(store.remaining("token:a")).isEqualTo(KeyValueStore.MISSING);
    }

    @Test
    void updateKeepsTheRemainingTtl() {
        store.set("token:a", "1", Duration.ofMinutes(30));
        clock.advance(Duration.ofMinutes(10));

        assertThat(store.update("token:a", "2")).isTrue();

        assertThat(store.get("token:a")).isEqualTo("2");
        assertThat(store.remaining("token:a")).isEqualTo(Duration.ofMinutes(20).toMillis());
        assertThat(store.update("missing", "x")).isFalse();
        assertThat(store.get("missing")).isNull();
    }

    @Test
    void persistentValuesAndChangingTheTtl() {
        store.set("session:1", "s", null);
        assertThat(store.remaining("session:1")).isEqualTo(KeyValueStore.PERSISTENT);

        store.expire("session:1", Duration.ofSeconds(5));
        assertThat(store.remaining("session:1")).isEqualTo(5_000);
        store.expire("session:1", null);
        assertThat(store.remaining("session:1")).isEqualTo(KeyValueStore.PERSISTENT);

        store.expire("missing", Duration.ofSeconds(5));
        assertThat(store.get("missing")).isNull();
    }

    @Test
    void keysByPrefixAndDelete() {
        store.set("login:token:a", "1", null);
        store.set("login:token:b", "2", null);
        store.set("login:session:1", "s", null);

        assertThat(store.keys("login:token:")).containsExactlyInAnyOrder("login:token:a", "login:token:b");
        store.delete("login:token:a");
        assertThat(store.keys("login:token:")).containsExactly("login:token:b");
    }

    @Test
    void storesWithTheSameNameShareData() {
        var stores = KeyValueStores.inMemory(clock);
        stores.store("sessions").set("k", "v", null);

        assertThat(stores.store("sessions").get("k")).isEqualTo("v");
        assertThat(stores.store("other").get("k")).isNull();
    }

    private static final class MutableClock extends Clock {

        private Instant now = Instant.parse("2026-11-05T00:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
