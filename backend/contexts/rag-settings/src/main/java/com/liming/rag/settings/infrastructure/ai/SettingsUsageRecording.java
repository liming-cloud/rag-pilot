package com.liming.rag.settings.infrastructure.ai;

import com.liming.rag.ai.UsageRecording;
import com.liming.rag.ai.common.ModelCallUsage;
import com.liming.rag.settings.application.model.ModelUsageService;
import org.springframework.stereotype.Component;

/** 实现 rag-ai 的 {@link UsageRecording}（#169、D-054）：每次模型调用结束后把用量交给 {@link ModelUsageService} 累加。 */
@Component
class SettingsUsageRecording implements UsageRecording {

    private final ModelUsageService usage;

    SettingsUsageRecording(ModelUsageService usage) {
        this.usage = usage;
    }

    @Override
    public void record(ModelCallUsage call) {
        usage.record(call.purpose(), call.baseUrl(), call.model(), call.inputTokens(), call.outputTokens());
    }
}
