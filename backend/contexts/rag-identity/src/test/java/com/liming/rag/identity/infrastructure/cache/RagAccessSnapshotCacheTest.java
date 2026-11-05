package com.liming.rag.identity.infrastructure.cache;

import static org.assertj.core.api.Assertions.assertThat;

import com.liming.rag.cache.Caches;
import com.liming.rag.identity.application.role.AccessSnapshot;
import java.util.List;
import org.junit.jupiter.api.Test;

class RagAccessSnapshotCacheTest {

    private final RagAccessSnapshotCache cache = new RagAccessSnapshotCache(Caches.inMemory());

    private static AccessSnapshot snapshot(long accountId, String... permissions) {
        return new AccessSnapshot(accountId, 1, true, List.of(3L), false, List.of(permissions));
    }

    @Test
    void cachedPerAccountUntilForgotten() {
        cache.get(1, () -> snapshot(1, "kb:view"));
        cache.get(2, () -> snapshot(2, "kb:view"));

        cache.forget(1);

        assertThat(cache.get(1, () -> snapshot(1))).isEqualTo(snapshot(1));
        assertThat(cache.get(2, () -> snapshot(2))).isEqualTo(snapshot(2, "kb:view"));
    }

    @Test
    void forgetAllReloadsEveryAccount() {
        cache.get(1, () -> snapshot(1, "kb:view"));
        cache.get(2, () -> snapshot(2, "kb:view"));

        cache.forgetAll();

        assertThat(cache.get(1, () -> snapshot(1))).isEqualTo(snapshot(1));
        assertThat(cache.get(2, () -> snapshot(2))).isEqualTo(snapshot(2));
    }

    @Test
    void missingAccountIsNotCached() {
        assertThat(cache.get(9, () -> null)).isNull();
        assertThat(cache.get(9, () -> snapshot(9, "chat:use"))).isEqualTo(snapshot(9, "chat:use"));
    }
}
