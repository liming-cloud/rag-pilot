package com.liming.rag.settings.application.model;

import com.liming.rag.ai.ModelUsage;
import java.time.OffsetDateTime;
import org.jspecify.annotations.Nullable;

/**
 * 一条变更：谁、什么时候、把哪个用途从什么换成什么。服务商名称记的是当时的，之后改名或删除也看得懂。
 *
 * @param beforeProviderName 第一次在界面配置时是环境变量里的那个
 * @param afterProviderId 用来恢复；null 表示环境变量里的服务商
 * @param restoredFrom 这条是恢复操作时，恢复的是哪条记录
 */
public record ModelChange(@Nullable Long id, ModelUsage purpose, String beforeProviderName, String beforeModel,
        @Nullable Long afterProviderId, String afterProviderName, String afterModel, long changedBy, String changedByName,
        OffsetDateTime changedAt, @Nullable Long restoredFrom) {
}
