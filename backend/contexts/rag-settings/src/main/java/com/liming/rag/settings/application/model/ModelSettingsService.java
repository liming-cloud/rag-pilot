package com.liming.rag.settings.application.model;

import com.liming.rag.settings.application.system.SettingsInputs;
import com.liming.rag.settings.application.SettingsException;
import com.liming.rag.settings.application.Paged;

import com.liming.rag.ai.ModelCallException;
import com.liming.rag.ai.ModelProbe;
import com.liming.rag.ai.ModelRoute;
import com.liming.rag.ai.ModelRouting;
import com.liming.rag.ai.ModelUsage;
import com.liming.rag.identity.api.UserDirectory;
import com.liming.rag.shared.TenantId;
import com.liming.rag.shared.UserId;
import java.net.URI;
import java.time.Clock;
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
 * 同时是 rag-ai 的 {@link ModelRouting}：每次模型调用前都读一次，保存后下一次请求就生效，不用重启。
 * 没在界面配置过的用途用环境变量。
 *
 * <p>连通测试、读服务商的模型列表都是外部调用，可能等到超时，所以不在数据库事务里做；测通之后才开一个短事务写库（#135）。
 */
@Service
public class ModelSettingsService implements ModelRouting {

    static final int MAX_MODEL_LENGTH = 200;

    private final ModelSettingsStore store;
    private final ModelProbe probe;
    private final KeyCipher cipher;
    private final UserDirectory users;
    private final TransactionOperations transactions;
    private final Clock clock;

    ModelSettingsService(ModelSettingsStore store, ModelProbe probe, KeyCipher cipher, UserDirectory users,
            TransactionOperations transactions, Clock clock) {
        this.store = store;
        this.probe = probe;
        this.cipher = cipher;
        this.users = users;
        this.transactions = transactions;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ModelRoute> override(ModelUsage usage) {
        try {
            return store.purpose(usage).map(setting -> route(usage, setting.providerId(), setting.model()));
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
        var provider = store.provider(providerId).orElseThrow(() -> notFound("服务商不存在"));
        var key = provider.apiKeyCipher() == null ? null : cipher.decrypt(provider.apiKeyCipher());
        return new ModelRoute(provider.baseUrl(), key, model);
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
