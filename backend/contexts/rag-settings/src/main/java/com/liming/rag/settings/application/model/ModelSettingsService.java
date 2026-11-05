package com.liming.rag.settings.application.model;

import com.liming.rag.settings.application.system.SettingsInputs;
import com.liming.rag.settings.application.SettingsException;
import com.liming.rag.shared.Paged;

import com.liming.rag.ai.ModelCallException;
import com.liming.rag.ai.ModelProbe;
import com.liming.rag.ai.ModelRoute;
import com.liming.rag.ai.ModelRouting;
import com.liming.rag.ai.ModelUsage;
import com.liming.rag.cache.CacheSpec;
import com.liming.rag.cache.Caches;
import com.liming.rag.cache.TypedCache;
import com.liming.rag.identity.api.UserDirectory;
import com.liming.rag.shared.TenantId;
import com.liming.rag.shared.UserId;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;

/**
 * 模型管理（D-043）：各用途用哪个服务商和模型、服务商、变更记录。保存和恢复前都先测连通，不通不保存；
 * 同时是 rag-ai 的 {@link ModelRouting}：每次模型调用前都问一次。各用途的配置和所用服务商缓存起来（D-053，密钥只存
 * 密文，每次在进程里解密），保存用途、改或删服务商后在提交后清除，下一次请求就生效，不用重启。没在界面配置过的用途
 * 用环境变量。
 *
 * <p>连通测试、读服务商的模型列表都是外部调用，可能等到超时，所以不在数据库事务里做；测通之后才开一个短事务写库（#135）。
 */
@Service
public class ModelSettingsService implements ModelRouting {

    static final int MAX_MODEL_LENGTH = 200;

    /**
     * 一个用途的路由在缓存里的样子：没在界面配置过时 {@code setting} 为 null；{@code provider} 是它用的服务商（密钥是密文），
     * 用环境变量的服务商或服务商已被删除时为 null。过期时间只是兜底，以清除为准。
     */
    record CachedRoute(@Nullable PurposeSetting setting, @Nullable ModelProvider provider) {
    }

    static final CacheSpec<CachedRoute> ROUTES = new CacheSpec<>("model-routes", CachedRoute.class, Duration.ofHours(1));

    private final ModelSettingsStore store;
    private final ModelProbe probe;
    private final KeyCipher cipher;
    private final UserDirectory users;
    private final TransactionOperations transactions;
    private final Clock clock;
    private final TypedCache<CachedRoute> routes;

    ModelSettingsService(ModelSettingsStore store, ModelProbe probe, KeyCipher cipher, UserDirectory users,
            TransactionOperations transactions, Clock clock, Caches caches) {
        this.store = store;
        this.probe = probe;
        this.cipher = cipher;
        this.users = users;
        this.transactions = transactions;
        this.clock = clock;
        this.routes = caches.cache(ROUTES);
    }

    /** 读缓存；没有时查用途和服务商两条语句，不开事务，不占着连接等模型。 */
    @Override
    public Optional<ModelRoute> override(ModelUsage usage) {
        var cached = routes.get(usage.name(), () -> {
            var setting = store.purpose(usage).orElse(null);
            var providerId = setting == null ? null : setting.providerId();
            return new CachedRoute(setting, providerId == null ? null : store.provider(providerId).orElse(null));
        });
        if (cached == null || cached.setting() == null) {
            return Optional.empty();
        }
        var setting = cached.setting();
        try {
            if (setting.providerId() == null) {
                return Optional.of(route(usage, null, setting.model()));
            }
            var provider = cached.provider();
            if (provider == null) {
                throw notFound("服务商不存在");
            }
            return Optional.of(route(provider, setting.model()));
        } catch (SettingsException e) {
            // 密钥解不开（主密钥变了）时，问答按“模型服务不可用”处理，说明原因。
            throw new ModelCallException(e.getMessage(), e);
        }
    }

    @Transactional(readOnly = true)
    public Overview overview() {
        var purposes = Arrays.stream(ModelUsage.values()).map(this::view).toList();
        var environment = probe.environment(ModelUsage.ANSWER);
        var providers = new ArrayList<ProviderView>();
        providers.add(new ProviderView(null, environmentName(), environment.baseUrl(), ModelProviderService.last4(environment.apiKey()),
                false, usedBy(purposes, null)));
        for (var provider : store.providers()) {
            providers.add(new ProviderView(provider.id(), provider.name(), provider.baseUrl(), provider.apiKeyLast4(),
                    true, usedBy(purposes, provider.id())));
        }
        var embedding = probe.embedding();
        return new Overview(cipher.available(), providers, purposes, environmentName(), embedding.model(),
                probe.embeddingDimensions());
    }

    /** 只测试，不保存；{@code usage} 是用途的名字。 */
    public ModelProbe.ProbeResult test(String usage, @Nullable Long providerId, String model) {
        var parsed = SettingsInputs.usage(usage);
        return probe.probe(parsed, route(parsed, providerId, checkModel(model)));
    }

    /** 服务商提供的模型，用来在界面上选。 */
    public List<String> availableModels(@Nullable Long providerId) {
        var route = route(ModelUsage.ANSWER, providerId, "");
        return probe.models(route.baseUrl(), route.apiKey());
    }

    /** 先测连通，通了才保存并记一条变更，下一次请求就用新模型；{@code usage} 是用途的名字。 */
    public PurposeView save(TenantId tenantId, UserId actor, String usage, @Nullable Long providerId, String model) {
        return save(tenantId, actor, SettingsInputs.usage(usage), providerId, model);
    }

    PurposeView save(TenantId tenantId, UserId actor, ModelUsage usage, @Nullable Long providerId, String model) {
        return apply(tenantId, actor, usage, providerId, checkModel(model), null);
    }

    /** 恢复到某条记录之后的配置，同样先测连通。 */
    public PurposeView restore(TenantId tenantId, UserId actor, long changeId) {
        var change = store.change(changeId).orElseThrow(() -> notFound("变更记录不存在"));
        if (change.afterProviderId() != null && store.provider(change.afterProviderId()).isEmpty()) {
            throw new SettingsException(SettingsException.Reason.CONFLICT, "这条记录用的服务商已被删除，不能恢复");
        }
        return apply(tenantId, actor, change.purpose(), change.afterProviderId(), change.afterModel(), change.id());
    }

    @Transactional(readOnly = true)
    public Paged<ModelChange> changes(int page, int size) {
        return store.changes(new Paged.Request(page, size));
    }

    private PurposeView apply(TenantId tenantId, UserId actor, ModelUsage usage, @Nullable Long providerId, String model,
            @Nullable Long restoredFrom) {
        // 外部调用在事务外：连通测试可能等到超时。
        var result = probe.probe(usage, route(usage, providerId, model));
        if (!result.ok()) {
            throw new SettingsException(SettingsException.Reason.UNREACHABLE,
                    "连通测试没通过，没有" + (restoredFrom == null ? "保存" : "恢复") + "：" + result.message());
        }
        var actorName = SettingsInputs.actorName(users, tenantId, actor);
        return transactions.execute(status -> {
            var before = view(usage);
            store.savePurpose(new PurposeSetting(usage, providerId, model, actorName, OffsetDateTime.now(clock)));
            routes.clear();
            store.insertChange(new ModelChange(null, usage, before.providerName(), before.model(), providerId,
                    providerName(providerId), model, actor.value(), actorName, OffsetDateTime.now(clock), restoredFrom));
            return view(usage);
        });
    }

    private PurposeView view(ModelUsage usage) {
        return store.purpose(usage)
                .map(setting -> new PurposeView(usage, setting.providerId(), providerName(setting.providerId()),
                        setting.model(), true, setting.updatedByName(), setting.updatedAt()))
                .orElseGet(() -> new PurposeView(usage, null, environmentName(), probe.environment(usage).model(),
                        false, null, null));
    }

    /** 这次调用打到哪里：providerId 为 null 用环境变量的地址和密钥。 */
    private ModelRoute route(ModelUsage usage, @Nullable Long providerId, String model) {
        if (providerId == null) {
            var environment = probe.environment(usage);
            return new ModelRoute(environment.baseUrl(), environment.apiKey(), model);
        }
        return route(store.provider(providerId).orElseThrow(() -> notFound("服务商不存在")), model);
    }

    private ModelRoute route(ModelProvider provider, String model) {
        var key = provider.apiKeyCipher() == null ? null : cipher.decrypt(provider.apiKeyCipher());
        return new ModelRoute(provider.baseUrl(), key, model);
    }

    /** 改或删了服务商：用到它的用途要重新读，提交后清除全部路由（D-053）。 */
    void forgetRoutes() {
        routes.clear();
    }

    private String providerName(@Nullable Long providerId) {
        if (providerId == null) {
            return environmentName();
        }
        return store.provider(providerId).map(ModelProvider::name).orElse("已删除的服务商");
    }

    /** 环境变量里的服务商显示为“环境变量（域名）”。 */
    private String environmentName() {
        var baseUrl = probe.environment(ModelUsage.ANSWER).baseUrl();
        String host;
        try {
            host = URI.create(baseUrl).getHost();
        } catch (IllegalArgumentException e) {
            host = null;
        }
        return host == null ? "环境变量" : "环境变量（" + host + "）";
    }

    private static List<ModelUsage> usedBy(List<PurposeView> purposes, @Nullable Long providerId) {
        return purposes.stream()
                .filter(view -> providerId == null ? view.providerId() == null : providerId.equals(view.providerId()))
                .map(PurposeView::purpose)
                .toList();
    }

    private static String checkModel(String model) {
        var trimmed = model == null ? "" : model.strip();
        if (trimmed.isEmpty() || trimmed.length() > MAX_MODEL_LENGTH) {
            throw invalid("模型名称必须是 1 到 " + MAX_MODEL_LENGTH + " 个字符");
        }
        return trimmed;
    }

    private static SettingsException invalid(String message) {
        return new SettingsException(SettingsException.Reason.INVALID, message);
    }

    private static SettingsException notFound(String message) {
        return new SettingsException(SettingsException.Reason.NOT_FOUND, message);
    }
}
