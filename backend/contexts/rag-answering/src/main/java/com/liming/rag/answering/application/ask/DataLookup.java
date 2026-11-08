package com.liming.rag.answering.application.ask;

import com.liming.rag.answering.application.ModelUnavailableException;
import com.liming.rag.data.api.DataQueries;
import com.liming.rag.shared.TenantId;
import com.liming.rag.shared.UserId;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 查数据步骤（#182）：提问人有可用的工具时，让模型判断要不要查、选哪个工具、填什么参数（{@link ToolSelection}），
 * 再经 rag-data 按提问人的数据范围查询。模型选了不在可用清单里的工具就不调。没有可用工具时不调用模型。
 * 选工具失败（模型不可用、输出不合格式）时按不查数据处理，回答只依据资料。
 */
@Component
public class DataLookup {

    static final String QUERYING_STATUS = "正在查询数据…";

    private static final Logger log = LoggerFactory.getLogger(DataLookup.class);

    private final DataQueries data;
    private final ToolSelection selection;

    public DataLookup(DataQueries data, ToolSelection selection) {
        this.data = data;
        this.selection = selection;
    }

    /** @return 不用查数据时为 null */
    @Nullable DataEvidence lookup(TenantId tenantId, UserId userId, String question, String history,
            Consumer<String> onStatus) {
        var catalog = data.catalog(tenantId, userId);
        if (catalog.tools().isEmpty()) {
            return null;
        }
        ToolChoice choice;
        try {
            choice = selection.select(ToolSelectionPrompt.SYSTEM, ToolSelectionPrompt.user(catalog, question, history))
                    .orElse(null);
        } catch (ModelUnavailableException e) {
            log.warn("event=tool_selection_failed reason=model_unavailable");
            return null;
        }
        if (choice == null) {
            return null;
        }
        var tool = choice.tool();
        if (catalog.tools().stream().noneMatch(available -> available.name().equals(tool))) {
            log.warn("event=tool_selection_rejected reason=not_available");
            return null;
        }
        onStatus.accept(QUERYING_STATUS);
        return new DataEvidence(tool, data.query(tenantId, userId, tool, choice.arguments()));
    }
}
