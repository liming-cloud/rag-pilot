package com.liming.rag.settings.infrastructure.persistence;

import java.time.OffsetDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** model_provider、model_purpose、model_change 三张表，SQL 写在 {@code mapper/settings/ModelSettingsMapper.xml}。 */
@Mapper
interface ModelSettingsMapper {

    List<ModelProviderDO> providers(@Param("id") Long id);

    boolean providerNameTaken(@Param("name") String name, @Param("exceptId") Long exceptId);

    long insertProvider(@Param("name") String name, @Param("baseUrl") String baseUrl,
            @Param("apiKeyCipher") byte[] apiKeyCipher, @Param("apiKeyLast4") String apiKeyLast4);

    void updateProvider(@Param("id") long id, @Param("name") String name, @Param("baseUrl") String baseUrl,
            @Param("apiKeyCipher") byte[] apiKeyCipher, @Param("apiKeyLast4") String apiKeyLast4);

    void deleteProvider(@Param("id") long id);

    List<ModelPurposeDO> purposes(@Param("purpose") String purpose);

    void savePurpose(@Param("purpose") String purpose, @Param("providerId") Long providerId,
            @Param("model") String model, @Param("updatedByName") String updatedByName,
            @Param("updatedAt") OffsetDateTime updatedAt);

    List<ModelChangeDO> changes(@Param("id") Long id, @Param("offset") long offset, @Param("size") int size);

    long countChanges();

    void insertChange(@Param("purpose") String purpose, @Param("beforeProviderName") String beforeProviderName,
            @Param("beforeModel") String beforeModel, @Param("afterProviderId") Long afterProviderId,
            @Param("afterProviderName") String afterProviderName, @Param("afterModel") String afterModel,
            @Param("changedBy") long changedBy, @Param("changedByName") String changedByName,
            @Param("changedAt") OffsetDateTime changedAt, @Param("restoredFrom") Long restoredFrom);
}
