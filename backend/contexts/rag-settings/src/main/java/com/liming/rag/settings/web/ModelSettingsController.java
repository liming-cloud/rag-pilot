package com.liming.rag.settings.web;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.liming.rag.identity.api.CurrentUser;
import com.liming.rag.settings.api.SettingsPermissions;
import com.liming.rag.settings.application.model.ModelChange;
import com.liming.rag.settings.application.model.ModelConnector;
import com.liming.rag.settings.application.model.ModelProviderService;
import com.liming.rag.settings.application.model.ModelSettingsService;
import com.liming.rag.settings.application.model.ProviderView;
import com.liming.rag.settings.application.model.PurposeView;
import com.liming.rag.shared.Paged;
import java.time.OffsetDateTime;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 模型管理接口（D-043）：查看要 model:view，测试连通要 model:test，保存、恢复、改服务商和读服务商模型列表要
 * model:update。响应里不出现密钥明文，只有后 4 位。
 */
@RestController
@SaCheckLogin
class ModelSettingsController {

    record TestRequest(String usage, @Nullable Long providerId, String model) {
    }

    record SaveRequest(@Nullable Long providerId, String model) {
    }

    record ProviderRequest(String name, String baseUrl, String apiKey) {
    }

    record UsageView(String usage, @Nullable Long providerId, String providerName, String model, String source,
            OffsetDateTime updatedAt, String updatedBy) {
        static UsageView of(PurposeView view) {
            return new UsageView(view.purpose().name(), view.providerId(), view.providerName(), view.model(),
                    sourceOf(view.fromDatabase()), view.updatedAt(), view.updatedBy());
        }
    }

    record ProviderResponse(@Nullable Long id, String name, String baseUrl, String apiKeyLast4, String source,
            List<String> usedBy) {
        static ProviderResponse of(ProviderView view) {
            return new ProviderResponse(view.id(), view.name(), view.baseUrl(), view.apiKeyLast4(),
                    sourceOf(view.fromDatabase()), view.usedBy().stream().map(Enum::name).toList());
        }
    }

    record EmbeddingView(String providerName, String model, int dimensions) {
    }

    record SettingsView(boolean masterKeyConfigured, List<ProviderResponse> providers, List<UsageView> usages,
            EmbeddingView embedding) {
    }

    record Side(String providerName, String model) {
    }

    record ChangeView(long id, String usage, Side before, Side after, String changedBy, OffsetDateTime changedAt,
            @Nullable Long restoredFrom) {
        static ChangeView of(ModelChange change) {
            var before = change.beforeModel() == null ? null
                    : new Side(change.beforeProviderName(), change.beforeModel());
            return new ChangeView(change.id(), change.purpose().name(), before,
                    new Side(change.afterProviderName(), change.afterModel()), change.changedByName(),
                    change.changedAt(), change.restoredFrom());
        }
    }

    private final ModelSettingsService settings;
    private final ModelProviderService providers;
    private final CurrentUser currentUser;

    ModelSettingsController(ModelSettingsService settings, ModelProviderService providers, CurrentUser currentUser) {
        this.settings = settings;
        this.providers = providers;
        this.currentUser = currentUser;
    }

    @SaCheckPermission(SettingsPermissions.MODEL_VIEW)
    @GetMapping("/api/models")
    SettingsView overview() {
        var overview = settings.overview();
        return new SettingsView(overview.masterKeyConfigured(),
                overview.providers().stream().map(ProviderResponse::of).toList(),
                overview.purposes().stream().map(UsageView::of).toList(),
                new EmbeddingView(overview.embeddingProviderName(), overview.embeddingModel(),
                        overview.embeddingDimensions()));
    }

    @SaCheckPermission(SettingsPermissions.MODEL_TEST)
    @PostMapping("/api/models/test")
    ModelConnector.ProbeResult test(@RequestBody TestRequest request) {
        return settings.test(request.usage(), request.providerId(), request.model());
    }

    @SaCheckPermission(SettingsPermissions.MODEL_UPDATE)
    @GetMapping("/api/models/available")
    List<String> available(@RequestParam(name = "providerId", required = false) Long providerId) {
        return settings.availableModels(providerId);
    }

    @SaCheckPermission(SettingsPermissions.MODEL_VIEW)
    @GetMapping("/api/models/changes")
    Paged<ChangeView> changes(Paged.Request page) {
        return settings.changes(page).map(ChangeView::of);
    }

    @SaCheckPermission(SettingsPermissions.MODEL_UPDATE)
    @PostMapping("/api/models/changes/{id}/restore")
    UsageView restore(@PathVariable long id) {
        return UsageView.of(settings.restore(currentUser.tenantId(), currentUser.userId(), id));
    }

    @SaCheckPermission(SettingsPermissions.MODEL_UPDATE)
    @PutMapping("/api/models/{usage}")
    UsageView save(@PathVariable String usage, @RequestBody SaveRequest request) {
        return UsageView.of(settings.save(currentUser.tenantId(), currentUser.userId(), usage,
                request.providerId(), request.model()));
    }

    @SaCheckPermission(SettingsPermissions.MODEL_UPDATE)
    @PostMapping("/api/model-providers")
    @ResponseStatus(HttpStatus.CREATED)
    ProviderResponse createProvider(@RequestBody ProviderRequest request) {
        return ProviderResponse.of(providers.createProvider(request.name(), request.baseUrl(), request.apiKey()));
    }

    @SaCheckPermission(SettingsPermissions.MODEL_UPDATE)
    @PatchMapping("/api/model-providers/{id}")
    ProviderResponse updateProvider(@PathVariable long id, @RequestBody ProviderRequest request) {
        return ProviderResponse.of(providers.updateProvider(id, request.name(), request.baseUrl(), request.apiKey()));
    }

    @SaCheckPermission(SettingsPermissions.MODEL_UPDATE)
    @DeleteMapping("/api/model-providers/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void deleteProvider(@PathVariable long id) {
        providers.deleteProvider(id);
    }

    private static String sourceOf(boolean fromDatabase) {
        return fromDatabase ? "DATABASE" : "ENVIRONMENT";
    }
}
