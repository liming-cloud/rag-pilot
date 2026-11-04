package com.liming.rag.settings.application;

import com.liming.rag.ai.ModelUsage;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** 模型管理的存储端口。 */
public interface ModelSettingsStore {

    /** 按添加顺序。 */
    List<ModelProvider> providers();

    Optional<ModelProvider> provider(long id);

    boolean providerNameTaken(String name, @Nullable Long exceptId);

    ModelProvider insertProvider(ModelProvider provider);

    /** 改名称和地址；{@code apiKeyCipher} 为 null 时不改密钥。 */
    void updateProvider(ModelProvider provider);

    void deleteProvider(long id);

    List<PurposeSetting> purposes();

    Optional<PurposeSetting> purpose(ModelUsage purpose);

    void savePurpose(PurposeSetting setting);

    /** 最新的在前。 */
    Paged<ModelChange> changes(Paged.Request page);

    Optional<ModelChange> change(long id);

    void insertChange(ModelChange change);
}
