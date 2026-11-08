package com.liming.rag.data.infrastructure.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.liming.rag.ai.ToolCall;
import com.liming.rag.ai.ToolResult;
import com.liming.rag.data.application.DataException;
import com.liming.rag.data.application.DataProperties;
import com.liming.rag.data.application.Fakes;
import com.liming.rag.data.application.ScopeUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ToolUnitDirectoryTest {

    private final List<ToolCall> calls = new ArrayList<>();

    private ToolUnitDirectory directory(Object data) {
        return new ToolUnitDirectory(call -> {
            calls.add(call);
            return new ToolResult(Map.of("data", data), "jti-1");
        }, new DataProperties("retail", "list_stores", List.of()));
    }

    @Test
    @DisplayName("用服务身份、空数据范围调配置的工具；站点缺名称时用编号，缺编号或区域的行忽略")
    void readsUnitsWithServiceIdentity() {
        var units = directory(List.of(
                Map.of("store_id", "S1", "name", "一店", "region", "east"),
                Map.of("store_id", "S2", "region", "west"),
                Map.of("name", "没有编号", "region", "east"),
                Map.of("store_id", "S3", "name", "没有区域"))).units(Fakes.TENANT);

        assertThat(units).containsExactly(new ScopeUnit("S1", "一店", "east"), new ScopeUnit("S2", "S2", "west"));
        assertThat(calls).singleElement().satisfies(call -> {
            assertThat(call.endpoint()).isEqualTo("retail");
            assertThat(call.tool()).isEqualTo("list_stores");
            assertThat(call.subject()).isEqualTo("service:rag-pilot");
            assertThat(call.dataScope()).isEmpty();
        });
    }

    @Test
    @DisplayName("返回的不是数组：按取不到处理，不当作没有站点")
    void malformedResultIsUnavailable() {
        assertThatThrownBy(() -> directory("oops").units(Fakes.TENANT)).isInstanceOf(DataException.class)
                .extracting(e -> ((DataException) e).reason()).isEqualTo(DataException.Reason.UNAVAILABLE);
    }
}
