package com.liming.rag.data.application.query;

import static org.assertj.core.api.Assertions.assertThat;

import com.liming.rag.data.api.DataCatalog.DataTool;
import com.liming.rag.data.api.DataCatalog.DataUnit;
import com.liming.rag.data.api.DataResult;
import com.liming.rag.data.application.DataGrantStore;
import com.liming.rag.data.application.DataProperties;
import com.liming.rag.data.application.Fakes;
import com.liming.rag.data.application.GranteeType;
import com.liming.rag.data.application.Grants;
import com.liming.rag.data.application.ScopeUnit;
import com.liming.rag.data.application.scope.DataScopeService;
import com.liming.rag.shared.TenantId;
import com.liming.rag.shared.UserId;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DataQueryServiceTest {

    private static final String TOOL = "get_store_inventory";
    private static final UserId USER = new UserId(1);

    private final Fakes.MemoryGrants grants = new Fakes.MemoryGrants();
    private final Fakes.Master master = new Fakes.Master();
    private final Fakes.Audit audit = new Fakes.Audit();
    private final Tools tools = new Tools();
    private DataQueryService service;

    /** 记下发出的调用；返回值或失败由测试设置。 */
    static final class Tools implements DataTools {
        record Call(String tool, Map<String, Object> arguments, String subject, Set<String> units) {
        }

        final List<Call> calls = new ArrayList<>();
        int listed;
        Map<String, Object> content = Map.of();
        DataToolException failure;

        @Override
        public List<DataTool> list() {
            listed++;
            return List.of(new DataTool(TOOL, "库存", "按站点查库存", "{}"),
                    new DataTool("get_store_sales", "销售", "按站点查销售", "{}"),
                    new DataTool("not_configured", "", "部署没配置的工具", "{}"));
        }

        @Override
        public ToolReply call(String tool, Map<String, Object> arguments, String subject, Set<String> units) {
            calls.add(new Call(tool, arguments, subject, units));
            if (failure != null) {
                throw failure;
            }
            return new ToolReply(content, "jti-1");
        }
    }

    @BeforeEach
    void setUp() {
        master.units = List.of(new ScopeUnit("S001", "一店", "east"), new ScopeUnit("S002", "二店", "east"),
                new ScopeUnit("S003", "三店", "west"));
        var properties = new DataProperties("retail", "list_stores", List.of(new DataProperties.Tool(TOOL, "库存"),
                new DataProperties.Tool("get_store_sales", "销售"), new DataProperties.Tool("unlisted", "没提供的")));
        var clock = Clock.fixed(Instant.parse("2026-11-08T01:00:00Z"), ZoneOffset.UTC);
        service = new DataQueryService(new DataScopeService(grants, new Fakes.Directory(), master.master()),
                master.master(), tools, properties, new ToolCallAudit(audit), clock);
        tools.content = inventory(List.of(row("S001", 24)));
    }

    private void grant(Set<String> tools, Set<String> units, Set<String> regions) {
        grants.replace(Fakes.TENANT, GranteeType.USER, USER.value(), new Grants(tools, units, regions));
    }

    private static Map<String, Object> row(String store, int quantity) {
        return Map.of("store_id", store, "sku", "A1001", "name", "鲜牛奶 1L", "quantity", quantity);
    }

    private static Map<String, Object> inventory(List<Map<String, Object>> rows) {
        var content = new LinkedHashMap<String, Object>();
        content.put("data", rows);
        content.put("source", Map.of("system", "ERP", "dataset", "store_inventory"));
        content.put("query", Map.of("store_id", "S001", "sku", "A1001"));
        content.put("asOf", "2026-11-08T09:30:00+08:00");
        content.put("queriedAt", "2026-11-08T09:30:02+08:00");
        content.put("truncated", false);
        return content;
    }

    private DataResult query(Map<String, Object> arguments) {
        return service.query(Fakes.TENANT, USER, TOOL, arguments);
    }

    @Test
    @DisplayName("没有工具授权：目录为空，不向客户系统发任何请求")
    void catalogIsEmptyWithoutToolGrant() {
        grant(Set.of(), Set.of("S001"), Set.of());

        var catalog = service.catalog(Fakes.TENANT, USER);

        assertThat(catalog.tools()).isEmpty();
        assertThat(tools.listed).isZero();
    }

    @Test
    @DisplayName("部署没配置任何可授权的工具：目录为空，不查授权、不发请求（不给普通问答加查询）")
    void catalogIsEmptyWithoutConfiguredTools() {
        grant(Set.of(TOOL), Set.of("S001"), Set.of());
        var reads = new AtomicInteger();
        var counting = new DataGrantStore() {
            @Override
            public Grants find(TenantId tenantId, GranteeType type, long granteeId) {
                reads.incrementAndGet();
                return grants.find(tenantId, type, granteeId);
            }

            @Override
            public Grants findEffective(TenantId tenantId, UserId userId,
                    Collection<Long> roleIds) {
                reads.incrementAndGet();
                return grants.findEffective(tenantId, userId, roleIds);
            }

            @Override
            public void replace(TenantId tenantId, GranteeType type, long granteeId,
                    Grants replacement) {
                grants.replace(tenantId, type, granteeId, replacement);
            }

            @Override
            public void removeRole(TenantId tenantId, long roleId) {
                grants.removeRole(tenantId, roleId);
            }
        };
        var unconfigured = new DataQueryService(new DataScopeService(counting, new Fakes.Directory(), master.master()),
                master.master(), tools, new DataProperties("retail", "list_stores", List.of()),
                new ToolCallAudit(audit), Clock.systemUTC());

        assertThat(unconfigured.catalog(Fakes.TENANT, USER).tools()).isEmpty();
        assertThat(reads).hasValue(0);
        assertThat(tools.listed).isZero();
    }

    @Test
    @DisplayName("目录：只列授权了、部署配置了、客户系统也提供的工具，站点按授权展开并带名称")
    void catalogListsGrantedToolsAndUnits() {
        grant(Set.of(TOOL, "unlisted", "not_configured"), Set.of("S003"), Set.of("east"));

        var catalog = service.catalog(Fakes.TENANT, USER);

        assertThat(catalog.tools()).extracting(DataTool::name).containsExactly(TOOL);
        assertThat(catalog.units()).containsExactly(new DataUnit("S001", "一店"), new DataUnit("S002", "二店"),
                new DataUnit("S003", "三店"));
    }

    @Test
    @DisplayName("参数里的站点不在范围内：拒绝，不发请求，没有返回数据；审计记一条“拒绝”")
    void outOfScopeIsNotSent() {
        grant(Set.of(TOOL), Set.of("S001"), Set.of());

        var result = query(Map.of("store_id", "S002"));

        assertThat(result.status()).isEqualTo(DataResult.Status.DENIED);
        assertThat(result.rows()).isEmpty();
        assertThat(result.message()).contains("无权查看");
        assertThat(tools.calls).isEmpty();
        assertThat(audit.entries).singleElement().asString()
                .startsWith("DATA_TOOL_CALLED|DATA_TOOL|" + TOOL + "|结果=拒绝")
                .contains("条件=store_id=S002").contains("行数=0");
    }

    @Test
    @DisplayName("没有工具授权：拒绝，不发请求")
    void ungrantedToolIsNotSent() {
        grant(Set.of("get_store_sales"), Set.of("S001"), Set.of());

        var result = query(Map.of("store_id", "S001"));

        assertThat(result.status()).isEqualTo(DataResult.Status.DENIED);
        assertThat(tools.calls).isEmpty();
    }

    @Test
    @DisplayName("范围内：令牌范围只含参数里的站点，返回来源、条件、截至时间；范围外多返回的一行被丢弃并记进审计")
    void dropsRowsOutsideScope() {
        grant(Set.of(TOOL), Set.of("S001", "S002"), Set.of());
        tools.content = inventory(List.of(row("S001", 24), row("S002", 3), Map.of("sku", "X", "quantity", 1)));

        var result = query(Map.of("store_id", "S001", "sku", "A1001"));

        assertThat(result.status()).isEqualTo(DataResult.Status.OK);
        assertThat(result.rows()).containsExactly(row("S001", 24));
        assertThat(result.system()).isEqualTo("ERP");
        assertThat(result.dataset()).isEqualTo("store_inventory");
        assertThat(result.query()).isEqualTo(Map.of("store_id", "S001", "sku", "A1001"));
        assertThat(result.asOf()).isEqualTo(OffsetDateTime.parse("2026-11-08T09:30:00+08:00"));
        assertThat(result.queriedAt()).isEqualTo(OffsetDateTime.parse("2026-11-08T09:30:02+08:00"));
        assertThat(tools.calls).containsExactly(
                new Tools.Call(TOOL, Map.of("store_id", "S001", "sku", "A1001"), "1", Set.of("S001")));
        assertThat(audit.entries).singleElement().asString().contains("结果=成功").contains("行数=1")
                .contains("丢弃=2").contains("令牌=jti-1").contains("范围=站点1个")
                .doesNotContain("鲜牛奶").doesNotContain("24");
    }

    @Test
    @DisplayName("没指定站点：令牌范围是用户被授权的全部站点（区域已展开）")
    void unspecifiedUnitsUseWholeScope() {
        grant(Set.of(TOOL), Set.of(), Set.of("east"));

        query(Map.of());

        assertThat(tools.calls).singleElement().extracting(Tools.Call::units).isEqualTo(Set.of("S001", "S002"));
    }

    @Test
    @DisplayName("工具调用失败：如实说暂时查不到，没有数据；审计记失败分类")
    void failureIsReportedWithoutData() {
        grant(Set.of(TOOL), Set.of("S001"), Set.of());
        tools.failure = new DataToolException("UNAVAILABLE", "数据查询暂时不可用，暂时查不到。");

        var result = query(Map.of("store_id", "S001"));

        assertThat(result.status()).isEqualTo(DataResult.Status.FAILED);
        assertThat(result.rows()).isEmpty();
        assertThat(result.message()).isEqualTo("数据查询暂时不可用，暂时查不到。");
        assertThat(audit.entries).singleElement().asString().contains("结果=失败").contains("状态=UNAVAILABLE");
    }

    @Test
    @DisplayName("返回不符合约定（缺数据截至时间、行不是对象）：按失败处理，不用这份数据")
    void malformedResultIsFailure() {
        grant(Set.of(TOOL), Set.of("S001"), Set.of());
        var missing = new LinkedHashMap<>(inventory(List.of(row("S001", 24))));
        missing.remove("asOf");
        tools.content = missing;

        var result = query(Map.of("store_id", "S001"));

        assertThat(result.status()).isEqualTo(DataResult.Status.FAILED);
        assertThat(result.rows()).isEmpty();
        assertThat(result.message()).contains("暂时查不到");
        assertThat(audit.entries).singleElement().asString().contains("状态=PROTOCOL");

        var badRow = new LinkedHashMap<>(inventory(List.of(row("S001", 24))));
        badRow.put("data", List.of("S001,24"));
        tools.content = badRow;
        assertThat(query(Map.of("store_id", "S001")).status()).isEqualTo(DataResult.Status.FAILED);
    }
}
