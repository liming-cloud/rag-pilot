package com.liming.rag.settings.infrastructure.persistence;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.liming.rag.settings.application.model.ModelPurpose;
import com.liming.rag.settings.application.model.ModelChange;
import com.liming.rag.settings.application.model.ModelProvider;
import com.liming.rag.settings.application.model.ModelSettingsStore;
import com.liming.rag.settings.application.model.PurposeSetting;
import com.liming.rag.persistence.paging.Pages;
import com.liming.rag.shared.Paged;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Repository;

/** 三张表都是单表读写，用 MyBatis-Plus 的通用方法；只有用途的保存是 upsert，写在 XML 里。 */
@Repository
class MybatisModelSettingsStore implements ModelSettingsStore {

    private final ModelProviderMapper providers;
    private final ModelPurposeMapper purposes;
    private final ModelChangeMapper changes;
    private final Clock clock;

    MybatisModelSettingsStore(ModelProviderMapper providers, ModelPurposeMapper purposes, ModelChangeMapper changes,
            Clock clock) {
        this.providers = providers;
        this.purposes = purposes;
        this.changes = changes;
        this.clock = clock;
    }

    @Override
    public List<ModelProvider> providers() {
        return providers.selectList(new LambdaQueryWrapper<ModelProviderDO>().orderByAsc(ModelProviderDO::getId))
                .stream().map(MybatisModelSettingsStore::toProvider).toList();
    }

    @Override
    public Optional<ModelProvider> provider(long id) {
        return Optional.ofNullable(providers.selectById(id)).map(MybatisModelSettingsStore::toProvider);
    }

    @Override
    public boolean providerNameTaken(String name, @Nullable Long exceptId) {
        return providers.exists(new LambdaQueryWrapper<ModelProviderDO>()
                .eq(ModelProviderDO::getName, name)
                .ne(exceptId != null, ModelProviderDO::getId, exceptId));
    }

    @Override
    public ModelProvider insertProvider(ModelProvider provider) {
        var row = toRow(provider);
        providers.insert(row);
        return new ModelProvider(row.id, provider.name(), provider.baseUrl(), provider.apiKeyCipher(),
                provider.apiKeyLast4());
    }

    /** 不传密钥（为 null）时保留原来的密钥。 */
    @Override
    public void updateProvider(ModelProvider provider) {
        var row = toRow(provider);
        row.id = provider.id();
        providers.updateById(row);
    }

    @Override
    public void deleteProvider(long id) {
        providers.deleteById(id);
    }

    @Override
    public List<PurposeSetting> purposes() {
        return purposes.selectList(null).stream().map(MybatisModelSettingsStore::toPurpose).toList();
    }

    @Override
    public Optional<PurposeSetting> purpose(ModelPurpose purpose) {
        return Optional.ofNullable(purposes.selectById(purpose.name())).map(MybatisModelSettingsStore::toPurpose);
    }

    @Override
    public void savePurpose(PurposeSetting setting) {
        purposes.save(setting.purpose().name(), setting.providerId(), setting.model(), setting.updatedByName(),
                setting.updatedAt());
    }

    @Override
    public Paged<ModelChange> changes(Paged.Request page) {
        var found = changes.selectPage(Pages.of(page), new LambdaQueryWrapper<ModelChangeDO>()
                .orderByDesc(ModelChangeDO::getChangedAt).orderByDesc(ModelChangeDO::getId));
        return Pages.toPaged(found, page, MybatisModelSettingsStore::toChange);
    }

    @Override
    public Optional<ModelChange> change(long id) {
        return Optional.ofNullable(changes.selectById(id)).map(MybatisModelSettingsStore::toChange);
    }

    @Override
    public void insertChange(ModelChange change) {
        var row = new ModelChangeDO();
        row.purpose = change.purpose().name();
        row.beforeProviderName = change.beforeProviderName();
        row.beforeModel = change.beforeModel();
        row.afterProviderId = change.afterProviderId();
        row.afterProviderName = change.afterProviderName();
        row.afterModel = change.afterModel();
        row.changedBy = change.changedBy();
        row.changedByName = change.changedByName();
        row.changedAt = change.changedAt();
        row.restoredFrom = change.restoredFrom();
        changes.insert(row);
    }

    private ModelProviderDO toRow(ModelProvider provider) {
        var row = new ModelProviderDO();
        row.name = provider.name();
        row.baseUrl = provider.baseUrl();
        row.apiKeyCipher = provider.apiKeyCipher();
        row.apiKeyLast4 = provider.apiKeyLast4();
        row.updatedAt = OffsetDateTime.now(clock);
        return row;
    }

    private static ModelProvider toProvider(ModelProviderDO row) {
        return new ModelProvider(row.id, row.name, row.baseUrl, row.apiKeyCipher, row.apiKeyLast4);
    }

    private static PurposeSetting toPurpose(ModelPurposeDO row) {
        return new PurposeSetting(ModelPurpose.valueOf(row.purpose), row.providerId, row.model, row.updatedByName,
                row.updatedAt);
    }

    private static ModelChange toChange(ModelChangeDO row) {
        return new ModelChange(row.id, ModelPurpose.valueOf(row.purpose), row.beforeProviderName, row.beforeModel,
                row.afterProviderId, row.afterProviderName, row.afterModel, row.changedBy, row.changedByName,
                row.changedAt, row.restoredFrom);
    }
}
