package com.liming.rag.answering.infrastructure.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.liming.rag.answering.application.ask.ToolChoice;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AiToolSelectionTest {

    @Test
    @DisplayName("取输出里的 JSON 对象（允许包在代码块里）；值为 null 的参数去掉")
    void parsesChoice() {
        var raw = "```json\n{\"tool\":\"get_store_inventory\",\"arguments\":{\"store_id\":\"S001\",\"sku\":null,\"limit\":5}}\n```";

        assertThat(AiToolSelection.parse(raw))
                .contains(new ToolChoice("get_store_inventory", Map.of("store_id", "S001", "limit", 5)));
        assertThat(AiToolSelection.parse("{\"tool\":\"get_store_sales\"}"))
                .contains(new ToolChoice("get_store_sales", Map.of()));
    }

    @Test
    @DisplayName("不用查、格式不对（不是 JSON、工具名不是字符串、参数不是对象）：按不查数据处理")
    void rejectsOtherOutput() {
        assertThat(AiToolSelection.parse("{\"tool\":null}")).isEmpty();
        assertThat(AiToolSelection.parse("NO_ANSWER")).isEmpty();
        assertThat(AiToolSelection.parse("{\"tool\":\"x\",")).isEmpty();
        assertThat(AiToolSelection.parse("{\"tool\":[\"x\"]}")).isEmpty();
        assertThat(AiToolSelection.parse("{\"tool\":\"x\",\"arguments\":\"store_id=S001\"}")).isEmpty();
        assertThat(AiToolSelection.parse("{\"tool\":\" \"}")).isEmpty();
    }
}
