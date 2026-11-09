package com.liming.rag.settings.infrastructure.ai;

import com.liming.rag.ai.ModelRouting;
import com.liming.rag.ai.common.ModelCallException;
import com.liming.rag.ai.common.ModelRoute;
import com.liming.rag.ai.common.ModelUsage;
import com.liming.rag.settings.application.SettingsException;
import com.liming.rag.settings.application.model.ModelPurpose;
import com.liming.rag.settings.application.model.ModelSettingsService;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * 实现 rag-ai 的 {@link ModelRouting}（D-044、D-054）：每次模型调用前问一次，答案来自模型管理保存的配置。
 * 密钥解不开（主密钥变了）时，问答按“模型服务不可用”处理，说明原因。
 */
@Component
class SettingsModelRouting implements ModelRouting {

    private final ModelSettingsService settings;

    SettingsModelRouting(ModelSettingsService settings) {
        this.settings = settings;
    }

    @Override
    public Optional<ModelRoute> override(ModelUsage usage) {
        try {
            return settings.savedEndpoint(ModelPurpose.valueOf(usage.name())).map(AiModelConnector::route);
        } catch (SettingsException e) {
            throw new ModelCallException(e.getMessage(), e);
        }
    }
}
