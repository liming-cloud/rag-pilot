package com.liming.rag.data.application.query;

import com.liming.rag.data.api.DataAccess;
import com.liming.rag.data.api.DataCatalog;
import com.liming.rag.data.api.DataCatalog.DataTool;
import com.liming.rag.data.api.DataCatalog.DataUnit;
import com.liming.rag.data.api.DataQueries;
import com.liming.rag.data.api.DataResult;
import com.liming.rag.data.api.ScopeDecision;
import com.liming.rag.data.application.DataException;
import com.liming.rag.data.application.DataProperties;
import com.liming.rag.data.application.ScopeUnit;
import com.liming.rag.data.application.UnitMaster;
import com.liming.rag.data.application.scope.DataScopeService;
import com.liming.rag.shared.TenantId;
import com.liming.rag.shared.UserId;
import java.time.Clock;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 按提问人的数据范围调用客户系统的只读工具（D-058 的三道检查）：调用前经 {@link DataAccess} 判定，拒绝就不发请求；
 * 令牌的数据范围是判定放行的站点；返回后校验结构（{@link ToolOutput}），每一行的 {@code store_id} 必须在放行的站点里，
 * 其余的行丢弃（没有站点标识的行也丢弃）。每次调用记一条审计和一行 {@code event=tool_call} 日志。
 */
@Service
public class DataQueryService implements DataQueries {

    private static final Logger log = LoggerFactory.getLogger(DataQueryService.class);
    private static final Pattern TOOL_NAME = Pattern.compile("[A-Za-z0-9_.-]{1,64}");
    private static final String UNIT_FIELD = "store_id";
    private static final int MAX_CONDITIONS = 200;
    private static final String MALFORMED = "数据查询返回的格式不对，暂时查不到。";

    private final DataScopeService scope;
    private final UnitMaster master;
    private final DataTools tools;
    private final DataProperties properties;
    private final ToolCallAudit audit;
    private final Clock clock;

    public DataQueryService(DataScopeService scope, UnitMaster master, DataTools tools, DataProperties properties,
            ToolCallAudit audit, Clock clock) {
        this.scope = scope;
        this.master = master;
        this.tools = tools;
        this.properties = properties;
        this.audit = audit;
        this.clock = clock;
    }

    @Override
    public DataCatalog catalog(TenantId tenantId, UserId userId) {
        // 部署没配置可授权的工具时不查授权：每次提问都会走到这里，不给普通问答加查询。
        if (properties.tools().isEmpty()) {
            return DataCatalog.empty();
        }
        var available = scope.available(tenantId, userId);
        var configured = properties.tools().stream().filter(tool -> available.tools().contains(tool.name())).toList();
        if (configured.isEmpty()) {
            return DataCatalog.empty();
        }
        var names = configured.stream().map(DataProperties.Tool::name).collect(Collectors.toSet());
        return new DataCatalog(listed(configured).stream().filter(tool -> names.contains(tool.name())).toList(),
                units(tenantId, available.units()));
    }

    /** 客户系统提供的工具；取不到时用部署配置的名称，模型照样能选，调用时再如实报失败。 */
    private List<DataTool> listed(List<DataProperties.Tool> configured) {
        try {
            return tools.list();
        } catch (DataToolException e) {
            log.warn("event=tool_list_failed code={}", e.code());
            return configured.stream().map(tool -> new DataTool(tool.name(), tool.title(), tool.title(), "{}")).toList();
        }
    }

    private List<DataUnit> units(TenantId tenantId, Set<String> ids) {
        Map<String, String> names;
        try {
            names = master.units(tenantId).stream().collect(Collectors.toMap(ScopeUnit::id, ScopeUnit::name,
                    (first, second) -> first));
        } catch (DataException e) {
            names = Map.of();
        }
        var known = names;
        return ids.stream().sorted().map(id -> new DataUnit(id, known.getOrDefault(id, id))).toList();
    }

    @Override
    public DataResult query(TenantId tenantId, UserId userId, String tool, Map<String, Object> arguments) {
        var started = clock.millis();
        var units = values(arguments, UNIT_FIELD, "store_ids");
        var regions = values(arguments, "region", "regions");
        var decision = units == null || regions == null ? ScopeDecision.deny("查询条件不对，无权查看")
                : scope.decide(tenantId, userId, tool, units, regions);
        var call = new Call(tenantId, userId, tool, arguments, decision, regions == null ? 0 : regions.size(), started);
        if (!decision.allowed()) {
            finish(call, "拒绝", "-", 0, 0, false, "-");
            return DataResult.denied(decision.reason());
        }
        ToolReply reply;
        try {
            reply = tools.call(tool, arguments, String.valueOf(userId.value()), decision.units());
        } catch (DataToolException e) {
            finish(call, "失败", e.code(), 0, 0, false, "-");
            return DataResult.failed(e.getMessage());
        }
        var output = ToolOutput.parse(reply.content());
        if (output == null) {
            finish(call, "失败", "PROTOCOL", 0, 0, false, reply.tokenId());
            return DataResult.failed(MALFORMED);
        }
        var kept = output.rows().stream()
                .filter(row -> row.get(UNIT_FIELD) instanceof String unit && decision.units().contains(unit))
                .toList();
        finish(call, "成功", "-", kept.size(), output.rows().size() - kept.size(), output.truncated(), reply.tokenId());
        return new DataResult(DataResult.Status.OK, "", kept, output.system(), output.dataset(), output.query(),
                output.asOf(), output.queriedAt(), output.truncated());
    }

    /** 一次调用的上下文，用来记审计和日志。 */
    private record Call(TenantId tenantId, UserId userId, String tool, Map<String, Object> arguments,
            ScopeDecision decision, int regions, long started) {
    }

    /** 记审计（规范第 8 节）和 {@code event=tool_call} 日志；日志不带条件和用户，工具名不合规时不照写。 */
    private void finish(Call call, String outcome, String code, int rows, int dropped, boolean truncated, String jti) {
        var duration = clock.millis() - call.started();
        var detail = "结果=%s 条件=%s 范围=站点%d个，区域%d个 行数=%d 截断=%s 丢弃=%d 耗时=%dms 状态=%s 令牌=%s".formatted(outcome,
                conditions(call.arguments()), call.decision().units().size(), call.regions(), rows,
                truncated ? "是" : "否", dropped, duration, code, jti);
        var tool = TOOL_NAME.matcher(call.tool()).matches() ? call.tool() : "invalid";
        audit.record(call.tenantId(), call.userId(), tool, detail);
        log.info("event=tool_call tool={} outcome={} code={} rows={} dropped={} truncated={} units={} duration_ms={} jti={}",
                tool, outcome, code, rows, dropped, truncated, call.decision().units().size(), duration, jti);
        if (dropped > 0) {
            log.warn("event=tool_rows_dropped tool={} dropped={} jti={}", tool, dropped, jti);
        }
    }

    /** 参数里的业务条件，如 {@code store_id=S001，sku=A1001}，过长截断。 */
    private static String conditions(Map<String, Object> arguments) {
        var text = arguments.entrySet().stream().map(entry -> entry.getKey() + "=" + entry.getValue())
                .collect(Collectors.joining("，"));
        if (text.isEmpty()) {
            return "-";
        }
        return text.length() <= MAX_CONDITIONS ? text : text.substring(0, MAX_CONDITIONS) + "…";
    }

    /** 参数里单个（字符串）或多个（字符串数组）的站点或区域；类型不对时为 null，整体拒绝。 */
    private static @Nullable Set<String> values(Map<String, Object> arguments, String single, String multiple) {
        var values = new HashSet<String>();
        var one = arguments.get(single);
        if (one instanceof String text) {
            values.add(text);
        } else if (one != null) {
            return null;
        }
        var many = arguments.get(multiple);
        if (many instanceof Collection<?> items) {
            for (var item : items) {
                if (!(item instanceof String text)) {
                    return null;
                }
                values.add(text);
            }
        } else if (many != null) {
            return null;
        }
        return values;
    }
}
