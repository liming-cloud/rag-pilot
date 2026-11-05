package com.liming.rag.settings.infrastructure.cache;

import static org.assertj.core.api.Assertions.assertThat;

import com.liming.rag.ai.ModelUsage;
import com.liming.rag.cache.Caches;
import com.liming.rag.settings.application.SettingKey;
import com.liming.rag.settings.application.model.PurposeSetting;
import com.liming.rag.settings.application.model.SavedRoute;
import com.liming.rag.settings.application.system.SystemSettingsStore;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class RagSettingsCachesTest {

    private static final OffsetDateTime AT = OffsetDateTime.of(2026, 11, 5, 0, 0, 0, 0, ZoneOffset.UTC);

    private final Caches caches = Caches.inMemory();
    private final AtomicInteger loads = new AtomicInteger();

    @Test
    void savedSettingsAreCachedUntilForgotten() {
        var cache = new RagSavedSettingsCache(caches);
        var first = List.of(new SystemSettingsStore.Saved(SettingKey.BRAND_NAME, List.of("甲"), "管理员", AT));
        var second = List.of(new SystemSettingsStore.Saved(SettingKey.BRAND_NAME, List.of("乙"), "管理员", AT));

        assertThat(cache.get(() -> count(first))).isEqualTo(first);
        assertThat(cache.get(() -> count(second))).isEqualTo(first);
        cache.forget();
        assertThat(cache.get(() -> count(second))).isEqualTo(second);
        assertThat(loads).hasValue(2);
    }

    @Test
    void routesAreCachedPerUsageUntilForgotten() {
        var cache = new RagModelRouteCache(caches);
        var answer = route(ModelUsage.ANSWER, "model-a");

        assertThat(cache.get(ModelUsage.ANSWER, () -> count(answer))).isEqualTo(answer);
        assertThat(cache.get(ModelUsage.ANSWER, () -> count(route(ModelUsage.ANSWER, "changed")))).isEqualTo(answer);
        assertThat(cache.get(ModelUsage.RERANK, () -> count(new SavedRoute(null, null))))
                .isEqualTo(new SavedRoute(null, null));

        cache.forgetAll();
        assertThat(cache.get(ModelUsage.ANSWER, () -> count(route(ModelUsage.ANSWER, "model-b"))))
                .isEqualTo(route(ModelUsage.ANSWER, "model-b"));
        assertThat(loads).hasValue(3);
    }

    private static SavedRoute route(ModelUsage usage, String model) {
        return new SavedRoute(new PurposeSetting(usage, null, model, "管理员", AT), null);
    }

    private <T> T count(T value) {
        loads.incrementAndGet();
        return value;
    }
}
