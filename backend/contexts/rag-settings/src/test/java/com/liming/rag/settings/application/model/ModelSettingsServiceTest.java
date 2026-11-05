package com.liming.rag.settings.application.model;

import com.liming.rag.cache.Caches;
import com.liming.rag.settings.application.SettingsException;
import com.liming.rag.settings.application.SettingsProperties;
import com.liming.rag.shared.Paged;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.liming.rag.ai.ModelProbe;
import com.liming.rag.ai.ModelRoute;
import com.liming.rag.ai.ModelUsage;
import com.liming.rag.identity.api.UserDirectory;
import com.liming.rag.shared.TenantId;
import com.liming.rag.shared.UserId;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionOperations;

/**
 * 模型管理的规则（D-043）：测通才保存、每次保存记变更、恢复、在用的服务商不能删、路由按当前配置。
 * 服务商的增删改在 {@link ModelProviderService}（#145 拆出）；它和用途配置互相关联（在用的不能删、改密钥后路由跟着变），
 * 所以两者放在同一组测试里一起验证。
 */
class ModelSettingsServiceTest {

    private static final TenantId TENANT = new TenantId(1);
    private static final UserId ADMIN = new UserId(7);

    private final InMemoryStore store = new InMemoryStore();
    private final FakeProbe probe = new FakeProbe();
    private final ModelSettingsService service = new ModelSettingsService(store, probe,
            new KeyCipher(new SettingsProperties(KeyCipherTest.MASTER_KEY)), new FakeUsers(),
            TransactionOperations.withoutTransaction(), Clock.systemUTC(), Caches.inMemory());
    private final ModelProviderService providers = new ModelProviderService(store,
            new KeyCipher(new SettingsProperties(KeyCipherTest.MASTER_KEY)), service);

    @Test
    @DisplayName("没在界面配置过时全部用环境变量，路由返回空")
    void defaultsToEnvironment() {
        var overview = service.overview();

        assertThat(overview.purposes()).allSatisfy(view -> assertThat(view.fromDatabase()).isFalse());
        assertThat(overview.providers()).singleElement().satisfies(provider -> {
            assertThat(provider.name()).isEqualTo("环境变量（env.example）");
            assertThat(provider.apiKeyLast4()).isEqualTo("1234");
            assertThat(provider.usedBy()).containsExactly(ModelUsage.values());
        });
        assertThat(service.override(ModelUsage.ANSWER)).isEmpty();
    }

    @Test
    @DisplayName("测通才保存：保存后路由打到新服务商、带解密后的密钥，并记一条从环境变量换过来的变更")
    void savesAfterProbe() {
        var provider = providers.createProvider("DeepSeek 官方", "https://api.deepseek.com/v1/", "sk-deepseek-abcd");

        var saved = service.save(TENANT, ADMIN, ModelUsage.ANSWER, provider.id(), " deepseek-chat ");

        assertThat(saved.fromDatabase()).isTrue();
        assertThat(saved.updatedBy()).isEqualTo("超级管理员");
        assertThat(service.override(ModelUsage.ANSWER)).contains(
                new ModelRoute("https://api.deepseek.com/v1", "sk-deepseek-abcd", "deepseek-chat"));
        assertThat(probe.probed).containsExactly("ANSWER https://api.deepseek.com/v1 deepseek-chat");
        var change = store.changes.getFirst();
        assertThat(change.beforeProviderName()).isEqualTo("环境变量（env.example）");
        assertThat(change.beforeModel()).isEqualTo("env-answer");
        assertThat(change.afterModel()).isEqualTo("deepseek-chat");
        assertThat(change.changedByName()).isEqualTo("超级管理员");
    }

    @Test
    @DisplayName("测不通时不保存，不记变更，说明原因")
    void refusesWhenProbeFails() {
        probe.failWith = "服务商返回 404：Model does not exist.";

        assertThatThrownBy(() -> service.save(TENANT, ADMIN, ModelUsage.RERANK, null, "nope"))
                .isInstanceOf(SettingsException.class)
                .hasMessage("连通测试没通过，没有保存：服务商返回 404：Model does not exist.");
        assertThat(store.purposes).isEmpty();
        assertThat(store.changes).isEmpty();
    }

    @Test
    @DisplayName("恢复到某条记录之后的配置，记一条“恢复”；服务商删了的记录不能恢复")
    void restores() {
        var provider = providers.createProvider("自建", "http://10.0.0.8:8000/v1", null);
        service.save(TENANT, ADMIN, ModelUsage.LIGHT, provider.id(), "qwen-small");
        service.save(TENANT, ADMIN, ModelUsage.LIGHT, null, "env-other");
        long first = store.changes.getFirst().id();

        var restored = service.restore(TENANT, ADMIN, first);

        assertThat(restored.providerId()).isEqualTo(provider.id());
        assertThat(restored.model()).isEqualTo("qwen-small");
        assertThat(store.changes.getLast().restoredFrom()).isEqualTo(first);
        assertThat(service.override(ModelUsage.LIGHT).orElseThrow().apiKey()).isNull();

        service.save(TENANT, ADMIN, ModelUsage.LIGHT, null, "env-other");
        providers.deleteProvider(provider.id());
        assertThatThrownBy(() -> service.restore(TENANT, ADMIN, first)).hasMessageContaining("已被删除");
    }

    @Test
    @DisplayName("服务商：名称不能重复、地址要以 http(s) 开头、在用的不能删；密钥只显示后 4 位，太短的不显示")
    void providerRules() {
        var provider = providers.createProvider("硅基流动", "https://api.siliconflow.cn/v1", "sk-silicon-wxyz");
        assertThat(provider.apiKeyLast4()).isEqualTo("wxyz");
        assertThat(store.providers.get(provider.id()).apiKeyCipher()).isNotNull();
        assertThat(providers.createProvider("短密钥", "http://a/v1", "abc").apiKeyLast4()).isEqualTo("••");

        assertThatThrownBy(() -> providers.createProvider("硅基流动", "https://x/v1", null)).hasMessageContaining("同名");
        assertThatThrownBy(() -> providers.createProvider("坏地址", "ftp://x", null)).hasMessageContaining("http://");

        service.save(TENANT, ADMIN, ModelUsage.RERANK, provider.id(), "bge-reranker");
        assertThatThrownBy(() -> providers.deleteProvider(provider.id())).hasMessageContaining("先换掉才能删除");

        var renamed = providers.updateProvider(provider.id(), "硅基流动 2", "https://api.siliconflow.cn/v1", "");
        assertThat(renamed.apiKeyLast4()).as("留空不改密钥").isEqualTo("wxyz");
        assertThat(service.override(ModelUsage.RERANK).orElseThrow().apiKey()).isEqualTo("sk-silicon-wxyz");
    }

    @Test
    @DisplayName("没设主密钥时不能保存密钥，不需要密钥的服务商照常能加")
    void masterKeyMissing() {
        var noKey = new KeyCipher(new SettingsProperties(""));
        var withoutKey = new ModelSettingsService(store, probe, noKey, new FakeUsers(),
                TransactionOperations.withoutTransaction(), Clock.systemUTC(), Caches.inMemory());
        var providersWithoutKey = new ModelProviderService(store, noKey, withoutKey);

        assertThatThrownBy(() -> providersWithoutKey.createProvider("云服务", "https://x/v1", "sk-1234567890"))
                .hasMessageContaining("RAG_MASTER_KEY");
        assertThat(providersWithoutKey.createProvider("内网", "http://10.0.0.8/v1", null).apiKeyLast4()).isNull();
        assertThat(withoutKey.overview().masterKeyConfigured()).isFalse();
    }

    private static final class FakeProbe implements ModelProbe {

        final List<String> probed = new ArrayList<>();
        String failWith;

        @Override
        public ModelRoute environment(ModelUsage usage) {
            var model = switch (usage) {
                case ANSWER -> "env-answer";
                case HEDGE -> "env-hedge";
                case LIGHT -> "env-light";
                case RERANK -> "env-rerank";
            };
            return new ModelRoute("https://env.example/v1", "sk-env-1234", model);
        }

        @Override
        public ModelRoute embedding() {
            return new ModelRoute("https://env.example/v1", null, "bge-m3");
        }

        @Override
        public int embeddingDimensions() {
            return 1024;
        }

        @Override
        public ProbeResult probe(ModelUsage usage, ModelRoute route) {
            probed.add(usage + " " + route.baseUrl() + " " + route.model());
            return failWith == null ? new ProbeResult(true, 300L, "连通正常") : new ProbeResult(false, 200L, failWith);
        }

        @Override
        public List<String> models(String baseUrl, String apiKey) {
            return List.of("m1", "m2");
        }
    }

    private static final class FakeUsers implements UserDirectory {

        @Override
        public boolean exists(TenantId tenantId, UserId userId) {
            return true;
        }

        @Override
        public List<Long> roleIds(TenantId tenantId, UserId userId) {
            return List.of();
        }

        @Override
        public boolean isSuperAdmin(TenantId tenantId, UserId userId) {
            return true;
        }

        @Override
        public boolean roleExists(TenantId tenantId, long roleId) {
            return true;
        }

        @Override
        public Map<Long, UserSummary> summaries(TenantId tenantId, Collection<Long> userIds) {
            return Map.of(7L, new UserSummary(7, "admin", "超级管理员"));
        }
    }

    private static final class InMemoryStore implements ModelSettingsStore {

        final Map<Long, ModelProvider> providers = new LinkedHashMap<>();
        final Map<ModelUsage, PurposeSetting> purposes = new LinkedHashMap<>();
        final List<ModelChange> changes = new ArrayList<>();
        private long nextId = 1;

        @Override
        public List<ModelProvider> providers() {
            return List.copyOf(providers.values());
        }

        @Override
        public Optional<ModelProvider> provider(long id) {
            return Optional.ofNullable(providers.get(id));
        }

        @Override
        public boolean providerNameTaken(String name, Long exceptId) {
            return providers.values().stream().anyMatch(p -> p.name().equals(name) && !p.id().equals(exceptId));
        }

        @Override
        public ModelProvider insertProvider(ModelProvider provider) {
            var stored = new ModelProvider(nextId++, provider.name(), provider.baseUrl(), provider.apiKeyCipher(),
                    provider.apiKeyLast4());
            providers.put(stored.id(), stored);
            return stored;
        }

        @Override
        public void updateProvider(ModelProvider provider) {
            var old = providers.get(provider.id());
            providers.put(provider.id(), new ModelProvider(provider.id(), provider.name(), provider.baseUrl(),
                    provider.apiKeyCipher() == null ? old.apiKeyCipher() : provider.apiKeyCipher(),
                    provider.apiKeyLast4()));
        }

        @Override
        public void deleteProvider(long id) {
            providers.remove(id);
        }

        @Override
        public List<PurposeSetting> purposes() {
            return List.copyOf(purposes.values());
        }

        @Override
        public Optional<PurposeSetting> purpose(ModelUsage purpose) {
            return Optional.ofNullable(purposes.get(purpose));
        }

        @Override
        public void savePurpose(PurposeSetting setting) {
            purposes.put(setting.purpose(), setting);
        }

        @Override
        public Paged<ModelChange> changes(Paged.Request page) {
            return new Paged<>(changes.reversed(), changes.size(), page.page(), page.size());
        }

        @Override
        public Optional<ModelChange> change(long id) {
            return changes.stream().filter(change -> change.id() == id).findFirst();
        }

        @Override
        public void insertChange(ModelChange change) {
            changes.add(new ModelChange(nextId++, change.purpose(), change.beforeProviderName(), change.beforeModel(),
                    change.afterProviderId(), change.afterProviderName(), change.afterModel(), change.changedBy(),
                    change.changedByName(), change.changedAt(), change.restoredFrom()));
        }
    }
}
