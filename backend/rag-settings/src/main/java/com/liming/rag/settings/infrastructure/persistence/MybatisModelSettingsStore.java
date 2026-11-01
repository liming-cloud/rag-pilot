package com.liming.rag.settings.infrastructure.persistence;

import com.liming.rag.ai.ModelUsage;
import com.liming.rag.settings.application.ModelChange;
import com.liming.rag.settings.application.ModelProvider;
import com.liming.rag.settings.application.ModelSettingsStore;
import com.liming.rag.settings.application.Paged;
import com.liming.rag.settings.application.PurposeSetting;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
    public boolean providerNameTaken(String name, Long exceptId) {
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

    private static ModelProvider toProvider(Map<String, Object> row) {
        return new ModelProvider(number(row.get("id")), (String) row.get("name"), (String) row.get("base_url"),
                (byte[]) row.get("api_key_cipher"), (String) row.get("api_key_last4"));
    }

    private static PurposeSetting toPurpose(Map<String, Object> row) {
        return new PurposeSetting(ModelUsage.valueOf((String) row.get("purpose")), number(row.get("provider_id")),
                (String) row.get("model"), (String) row.get("updated_by_name"), timestamp(row.get("updated_at")));
    }

    private static ModelChange toChange(Map<String, Object> row) {
        return new ModelChange(number(row.get("id")), ModelUsage.valueOf((String) row.get("purpose")),
                (String) row.get("before_provider_name"), (String) row.get("before_model"),
                number(row.get("after_provider_id")), (String) row.get("after_provider_name"),
                (String) row.get("after_model"), number(row.get("changed_by")), (String) row.get("changed_by_name"),
                timestamp(row.get("changed_at")), number(row.get("restored_from")));
    }

    private static Long number(Object value) {
        return value == null ? null : ((Number) value).longValue();
    }

    /** 以 Map 读取时 timestamptz 是 java.sql.Timestamp，换成带时区的时间。 */
    private static OffsetDateTime timestamp(Object value) {
        return value instanceof Timestamp timestamp
                ? OffsetDateTime.ofInstant(timestamp.toInstant(), ZoneId.systemDefault())
                : (OffsetDateTime) value;
    }
}
