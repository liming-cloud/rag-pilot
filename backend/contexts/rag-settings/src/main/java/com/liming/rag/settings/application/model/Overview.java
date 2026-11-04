package com.liming.rag.settings.application.model;

import java.util.List;

/** 模型管理页的全部状态。 */
public record Overview(boolean masterKeyConfigured, List<ProviderView> providers, List<PurposeView> purposes,
        String embeddingProviderName, String embeddingModel, int embeddingDimensions) {
}
