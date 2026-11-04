package com.liming.rag.settings.application;

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
import org.springframework.dao.DuplicateKeyException;
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

    /** 一个用途当前的配置，给界面显示。 */
    public record PurposeView(ModelUsage purpose, Long providerId, String providerName, String model,
            boolean fromDatabase, String updatedBy, OffsetDateTime updatedAt) {
    }

    /** 一个服务商，给界面显示：密钥只给后 4 位；环境变量里的服务商 id 为 null。 */
    public record ProviderView(Long id, String name, String baseUrl, String apiKeyLast4, boolean fromDatabase,
            List<ModelUsage> usedBy) {
    }

    /** 模型管理页的全部状态。 */
    public record Overview(boolean masterKeyConfigured, List<ProviderView> providers, List<PurposeView> purposes,
            String embeddingProviderName, String embeddingModel, int embeddingDimensions) {
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
        providers.add(new ProviderView(null, environmentName(), environment.baseUrl(), last4(environment.apiKey()),
                false, usedBy(purposes, null)));
        for (var provider : store.providers()) {
            providers.add(new ProviderView(provider.id(), provider.name(), provider.baseUrl(), provider.apiKeyLast4(),
                    true, usedBy(purposes, provider.id())));
        }
        var embedding = probe.embedding();
        return new Overview(cipher.available(), providers, purposes, environmentName(), embedding.model(),
                probe.embeddingDimensions());
    }

    /** 只测试，不保存。 */
    public ModelProbe.ProbeResult test(ModelUsage usage, Long providerId, String model) {
        return probe.probe(usage, route(usage, providerId, checkModel(model)));
    }

    /** 服务商提供的模型，用来在界面上选。 */
    public List<String> availableModels(Long providerId) {
        var route = route(ModelUsage.ANSWER, providerId, "");
        return probe.models(route.baseUrl(), route.apiKey());
    }

    /** 先测连通，通了才保存并记一条变更，下一次请求就用新模型。 */
    public PurposeView save(TenantId tenantId, UserId actor, ModelUsage usage, Long providerId, String model) {
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

    @Transactional
    public ProviderView createProvider(String name, String baseUrl, String apiKey) {
        var checkedName = checkName(name, null);
        var checkedUrl = checkBaseUrl(baseUrl);
        var key = blankToNull(apiKey);
        ModelProvider created;
        try {
            created = store.insertProvider(new ModelProvider(null, checkedName, checkedUrl, encrypt(key), last4(key)));
        } catch (DuplicateKeyException e) {
            // 查重之后、插入之前别人添加了同名服务商：靠唯一约束兜底（#135）。
            throw nameTaken(checkedName);
        }
        return new ProviderView(created.id(), created.name(), created.baseUrl(), created.apiKeyLast4(), true,
                List.of());
    }

    /** {@code apiKey} 为空表示不改密钥。改了地址或密钥，正在用它的用途下一次请求就生效。 */
    @Transactional
    public ProviderView updateProvider(long id, String name, String baseUrl, String apiKey) {
        var existing = store.provider(id).orElseThrow(() -> notFound("服务商不存在"));
        var key = blankToNull(apiKey);
        var updated = new ModelProvider(id, checkName(name, id), checkBaseUrl(baseUrl), encrypt(key),
                key == null ? existing.apiKeyLast4() : last4(key));
        try {
            store.updateProvider(updated);
        } catch (DuplicateKeyException e) {
            throw nameTaken(updated.name());
        }
        var purposes = Arrays.stream(ModelUsage.values()).map(this::view).toList();
        return new ProviderView(id, updated.name(), updated.baseUrl(), updated.apiKeyLast4(), true,
                usedBy(purposes, id));
    }

    @Transactional
    public void deleteProvider(long id) {
        store.provider(id).orElseThrow(() -> notFound("服务商不存在"));
        if (store.purposes().stream().anyMatch(setting -> Long.valueOf(id).equals(setting.providerId()))) {
            throw new SettingsException(SettingsException.Reason.CONFLICT, "还有用途在用这个服务商，先换掉才能删除");
        }
        store.deleteProvider(id);
    }

    private PurposeView apply(TenantId tenantId, UserId actor, ModelUsage usage, Long providerId, String model,
            Long restoredFrom) {
        // 外部调用在事务外：连通测试可能等到超时。
        var result = probe.probe(usage, route(usage, providerId, model));
        if (!result.ok()) {
            throw new SettingsException(SettingsException.Reason.UNREACHABLE,
                    "连通测试没通过，没有" + (restoredFrom == null ? "保存" : "恢复") + "：" + result.message());
        }
        var actorName = users.summaries(tenantId, List.of(actor.value())).values().stream().findFirst()
                .map(UserDirectory.UserSummary::displayName).orElse("账号 " + actor.value());
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
    private ModelRoute route(ModelUsage usage, Long providerId, String model) {
        if (providerId == null) {
            var environment = probe.environment(usage);
            return new ModelRoute(environment.baseUrl(), environment.apiKey(), model);
        }
        var provider = store.provider(providerId).orElseThrow(() -> notFound("服务商不存在"));
        var key = provider.apiKeyCipher() == null ? null : cipher.decrypt(provider.apiKeyCipher());
        return new ModelRoute(provider.baseUrl(), key, model);
    }

    private String providerName(Long providerId) {
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

    private static List<ModelUsage> usedBy(List<PurposeView> purposes, Long providerId) {
        return purposes.stream()
                .filter(view -> providerId == null ? view.providerId() == null : providerId.equals(view.providerId()))
                .map(PurposeView::purpose)
                .toList();
    }

    private byte[] encrypt(String key) {
        return key == null ? null : cipher.encrypt(key);
    }

    private String checkName(String name, Long exceptId) {
        var trimmed = name == null ? "" : name.strip();
        if (trimmed.isEmpty() || trimmed.length() > 30) {
            throw invalid("服务商名称必须是 1 到 30 个字符");
        }
        if (store.providerNameTaken(trimmed, exceptId)) {
            throw nameTaken(trimmed);
        }
        return trimmed;
    }

    private static String checkBaseUrl(String baseUrl) {
        var trimmed = baseUrl == null ? "" : baseUrl.strip().replaceAll("/+$", "");
        if (!trimmed.matches("https?://\\S+") || trimmed.length() > 300) {
            throw invalid("地址要以 http:// 或 https:// 开头，填到 /v1 为止");
        }
        return trimmed;
    }

    private static String checkModel(String model) {
        var trimmed = model == null ? "" : model.strip();
        if (trimmed.isEmpty() || trimmed.length() > MAX_MODEL_LENGTH) {
            throw invalid("模型名称必须是 1 到 " + MAX_MODEL_LENGTH + " 个字符");
        }
        return trimmed;
    }

    /** 界面只显示后 4 位；太短的密钥（不到 8 位）连后 4 位也不显示，免得露出大半。 */
    private static String last4(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        return key.length() < 8 ? "••" : key.substring(key.length() - 4);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static SettingsException nameTaken(String name) {
        return new SettingsException(SettingsException.Reason.CONFLICT, "已有同名服务商：" + name);
    }

    private static SettingsException invalid(String message) {
        return new SettingsException(SettingsException.Reason.INVALID, message);
    }

    private static SettingsException notFound(String message) {
        return new SettingsException(SettingsException.Reason.NOT_FOUND, message);
    }
}
