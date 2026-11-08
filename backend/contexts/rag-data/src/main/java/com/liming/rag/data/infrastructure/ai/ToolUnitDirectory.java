package com.liming.rag.data.infrastructure.ai;

import com.liming.rag.ai.ToolCall;
import com.liming.rag.ai.ToolCallException;
import com.liming.rag.ai.ToolCaller;
import com.liming.rag.data.application.DataException;
import com.liming.rag.data.application.DataProperties;
import com.liming.rag.data.application.ScopeUnit;
import com.liming.rag.data.application.UnitDirectory;
import com.liming.rag.shared.TenantId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 经平台组件 rag-ai 调用客户的只读工具（默认 {@code list_stores}）读取站点主数据（D-059）。用服务身份发起，
 * 令牌的数据范围为空：主数据只有编号、名称、所属区域，不含业务数据。返回的每一行要有 {@code store_id}、{@code region}，
 * {@code name} 缺省时用编号。失败时只告诉用户“暂时取不到”，不带客户系统的原始信息。
 */
@Component
class ToolUnitDirectory implements UnitDirectory {

    private static final Logger log = LoggerFactory.getLogger(ToolUnitDirectory.class);
    private static final String SERVICE_SUBJECT = "service:rag-pilot";

    private final ToolCaller tools;
    private final DataProperties properties;

    ToolUnitDirectory(ToolCaller tools, DataProperties properties) {
        this.tools = tools;
        this.properties = properties;
    }

    @Override
    public List<ScopeUnit> units(TenantId tenantId) {
        try {
            var result = tools.call(new ToolCall(properties.endpoint(), properties.unitsTool(), Map.of(),
                    SERVICE_SUBJECT, Map.of()));
            return parse(result.content().get("data"));
        } catch (ToolCallException e) {
            log.warn("event=tool_call_failed purpose=unit_master reason={}", e.reason());
            throw new DataException(DataException.Reason.UNAVAILABLE, "站点信息暂时取不到，请稍后再试");
        }
    }

    private static List<ScopeUnit> parse(Object data) {
        if (!(data instanceof List<?> rows)) {
            throw new DataException(DataException.Reason.UNAVAILABLE, "站点信息暂时取不到，请稍后再试");
        }
        var units = new ArrayList<ScopeUnit>();
        for (var row : rows) {
            if (row instanceof Map<?, ?> map && map.get("store_id") instanceof String id && !id.isBlank()
                    && map.get("region") instanceof String region && !region.isBlank()) {
                units.add(new ScopeUnit(id, map.get("name") instanceof String name && !name.isBlank() ? name : id, region));
            }
        }
        return List.copyOf(units);
    }
}
