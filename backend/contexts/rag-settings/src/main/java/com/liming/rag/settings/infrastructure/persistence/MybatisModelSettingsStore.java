package com.liming.rag.settings.infrastructure.persistence;

import com.liming.rag.ai.ModelUsage;
import com.liming.rag.settings.application.ModelChange;
import com.liming.rag.settings.application.ModelProvider;
import com.liming.rag.settings.application.ModelSettingsStore;
import com.liming.rag.settings.application.Paged;
import com.liming.rag.settings.application.PurposeSetting;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Repository;

@Repository
class MybatisModelSettingsStore implements ModelSettingsStore {

    private final ModelSettingsMapper mapper;

    MybatisModelSettingsStore(ModelSettingsMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public List<ModelProvider> providers() {
        return mapper.providers(null).stream().map(MybatisModelSettingsStore::toProvider).toList();
    }

    @Override
    public Optional<ModelProvider> provider(long id) {
        return mapper.providers(id).stream().findFirst().map(MybatisModelSettingsStore::toProvider);
    }

    @Override
    public boolean providerNameTaken(String name, @Nullable Long exceptId) {
        return mapper.providerNameTaken(name, exceptId);
    }

    @Override
    public ModelProvider insertProvider(ModelProvider provider) {
        long id = mapper.insertProvider(provider.name(), provider.baseUrl(), provider.apiKeyCipher(),
                provider.apiKeyLast4());
        return new ModelProvider(id, provider.name(), provider.baseUrl(), provider.apiKeyCipher(),
                provider.apiKeyLast4());
    }

    @Override
    public void updateProvider(ModelProvider provider) {
        mapper.updateProvider(provider.id(), provider.name(), provider.baseUrl(), provider.apiKeyCipher(),
                provider.apiKeyLast4());
    }

    @Override
    public void deleteProvider(long id) {
        mapper.deleteProvider(id);
    }

    @Override
    public List<PurposeSetting> purposes() {
        return mapper.purposes(null).stream().map(MybatisModelSettingsStore::toPurpose).toList();
    }

    @Override
    public Optional<PurposeSetting> purpose(ModelUsage purpose) {
        return mapper.purposes(purpose.name()).stream().findFirst().map(MybatisModelSettingsStore::toPurpose);
    }

    @Override
    public void savePurpose(PurposeSetting setting) {
        mapper.savePurpose(setting.purpose().name(), setting.providerId(), setting.model(), setting.updatedByName(),
                setting.updatedAt());
    }

    @Override
    public Paged<ModelChange> changes(Paged.Request page) {
        var items = mapper.changes(null, page.offset(), page.size()).stream()
                .map(MybatisModelSettingsStore::toChange).toList();
        return new Paged<>(items, mapper.countChanges(), page.page(), page.size());
    }

    @Override
    public Optional<ModelChange> change(long id) {
        return mapper.changes(id, 0, 1).stream().findFirst().map(MybatisModelSettingsStore::toChange);
    }

    @Override
    public void insertChange(ModelChange change) {
        mapper.insertChange(change.purpose().name(), change.beforeProviderName(), change.beforeModel(),
                change.afterProviderId(), change.afterProviderName(), change.afterModel(), change.changedBy(),
                change.changedByName(), change.changedAt(), change.restoredFrom());
    }

    private static ModelProvider toProvider(ModelProviderDO row) {
        return new ModelProvider(row.id, row.name, row.baseUrl, row.apiKeyCipher, row.apiKeyLast4);
    }

    private static PurposeSetting toPurpose(ModelPurposeDO row) {
        return new PurposeSetting(ModelUsage.valueOf(row.purpose), row.providerId, row.model, row.updatedByName,
                row.updatedAt);
    }

    private static ModelChange toChange(ModelChangeDO row) {
        return new ModelChange(row.id, ModelUsage.valueOf(row.purpose), row.beforeProviderName, row.beforeModel,
                row.afterProviderId, row.afterProviderName, row.afterModel, row.changedBy, row.changedByName,
                row.changedAt, row.restoredFrom);
    }
}
