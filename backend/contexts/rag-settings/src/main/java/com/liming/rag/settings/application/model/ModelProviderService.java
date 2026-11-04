package com.liming.rag.settings.application.model;

import com.liming.rag.settings.application.system.SettingsInputs;
import com.liming.rag.settings.application.SettingsException;

import com.liming.rag.ai.ModelUsage;
import com.liming.rag.settings.application.model.ProviderView;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 模型服务商（D-043）：在界面添加、修改、删除 OpenAI 兼容的服务商（#145 从 {@link ModelSettingsService} 按用例拆出）。
 * 密钥用主密钥加密后存库，界面只显示后 4 位；还有用途在用的服务商不能删。
 */
@Service
public class ModelProviderService {

    private final ModelSettingsStore store;
    private final KeyCipher cipher;

    ModelProviderService(ModelSettingsStore store, KeyCipher cipher) {
        this.store = store;
        this.cipher = cipher;
    }

    @Transactional
    public ProviderView createProvider(String name, String baseUrl, String apiKey) {
        var checkedName = checkName(name, null);
        var checkedUrl = checkBaseUrl(baseUrl);
        var key = SettingsInputs.blankToNull(apiKey);
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
        var key = SettingsInputs.blankToNull(apiKey);
        var updated = new ModelProvider(id, checkName(name, id), checkBaseUrl(baseUrl), encrypt(key),
                key == null ? existing.apiKeyLast4() : last4(key));
        try {
            store.updateProvider(updated);
        } catch (DuplicateKeyException e) {
            throw nameTaken(updated.name());
        }
        return new ProviderView(id, updated.name(), updated.baseUrl(), updated.apiKeyLast4(), true, usedBy(id));
    }

    @Transactional
    public void deleteProvider(long id) {
        store.provider(id).orElseThrow(() -> notFound("服务商不存在"));
        if (store.purposes().stream().anyMatch(setting -> Long.valueOf(id).equals(setting.providerId()))) {
            throw new SettingsException(SettingsException.Reason.CONFLICT, "还有用途在用这个服务商，先换掉才能删除");
        }
        store.deleteProvider(id);
    }

    /** 在界面配置成用这个服务商的用途；没在界面配置过的用途用环境变量，不算。 */
    private List<ModelUsage> usedBy(long providerId) {
        return store.purposes().stream()
                .filter(setting -> Long.valueOf(providerId).equals(setting.providerId()))
                .map(PurposeSetting::purpose)
                .toList();
    }

    private byte @Nullable [] encrypt(@Nullable String key) {
        return key == null ? null : cipher.encrypt(key);
    }
    private String checkName(@Nullable String name, @Nullable Long exceptId) {
        var trimmed = name == null ? "" : name.strip();
        if (trimmed.isEmpty() || trimmed.length() > 30) {
            throw invalid("服务商名称必须是 1 到 30 个字符");
        }
        if (store.providerNameTaken(trimmed, exceptId)) {
            throw nameTaken(trimmed);
        }
        return trimmed;
    }
    private static String checkBaseUrl(@Nullable String baseUrl) {
        var trimmed = baseUrl == null ? "" : baseUrl.strip().replaceAll("/+$", "");
        if (!trimmed.matches("https?://\\S+") || trimmed.length() > 300) {
            throw invalid("地址要以 http:// 或 https:// 开头，填到 /v1 为止");
        }
        return trimmed;
    }
    /** 界面只显示后 4 位；太短的密钥（不到 8 位）连后 4 位也不显示，免得露出大半。 */
    static @Nullable String last4(@Nullable String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        return key.length() < 8 ? "••" : key.substring(key.length() - 4);
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
