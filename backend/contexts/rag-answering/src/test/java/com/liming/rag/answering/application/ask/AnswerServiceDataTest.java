package com.liming.rag.answering.application.ask;

import static org.assertj.core.api.Assertions.assertThat;

import com.liming.rag.answering.application.AnsweringProperties;
import com.liming.rag.answering.application.ModelUnavailableException;
import com.liming.rag.answering.application.TestExecutor;
import com.liming.rag.answering.application.TestSettings;
import com.liming.rag.answering.domain.DataCitation;
import com.liming.rag.answering.domain.RefusalReason;
import com.liming.rag.data.api.DataCatalog;
import com.liming.rag.data.api.DataCatalog.DataTool;
import com.liming.rag.data.api.DataCatalog.DataUnit;
import com.liming.rag.data.api.DataResult;
import com.liming.rag.knowledge.api.SearchHit;
import com.liming.rag.shared.TenantId;
import com.liming.rag.shared.UserId;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 数据问答（#182）：按模型选的工具查数据，数据和资料并列引用；数据被拒或失败时如实说明，不编数据。 */
class AnswerServiceDataTest {

    private static final TenantId TENANT = new TenantId(1);
    private static final UserId USER = new UserId(1);
    private static final String TOOL = "get_store_inventory";
    private static final SearchHit SHELF = new SearchHit(11, 1, "商品陈列.txt", "饮料", "冷柜饮料每天盘点两次。", 0.03);
    private static final OffsetDateTime AS_OF = OffsetDateTime.parse("2026-11-08T09:30:00+08:00");
    private static final OffsetDateTime QUERIED = OffsetDateTime.parse("2026-11-08T09:30:02+08:00");

    private final TestData data = new TestData();
    private final List<String> selections = new ArrayList<>();
    private final List<String> prompts = new ArrayList<>();
    private Function<String, Optional<ToolChoice>> choose = user -> Optional.of(
            new ToolChoice(TOOL, Map.of("store_id", "S001")));

    @BeforeEach
    void setUp() {
        data.catalog = new DataCatalog(List.of(new DataTool(TOOL, "库存查询", "按站点查库存", "{\"type\":\"object\"}")),
                List.of(new DataUnit("S001", "一店")));
    }

    private AskResult ask(List<SearchHit> hits, double score, String reply) {
        var lookup = new DataLookup(data, (system, user) -> {
            selections.add(user);
            return choose.apply(user);
        });
        var service = new AnswerService(query -> hits, (query, documents) -> hits.stream().map(hit -> score).toList(),
                (system, user, onDelta, stop) -> {
                    prompts.add(user);
                    return reply;
                }, (instructions, question) -> "", new AnsweringProperties(30, 8, 0.3, false, 0.03,
                        Duration.ofSeconds(3)), TestSettings.RETAIL, TestExecutor.VIRTUAL, lookup);
        return service.ask(TENANT, USER, List.of(), "一店冷柜饮料还剩多少");
    }

    private static DataResult inventory() {
        var query = new LinkedHashMap<String, Object>();
        query.put("store_id", "S001");
        query.put("sku", "A1001");
        return new DataResult(DataResult.Status.OK, "", List.of(Map.of("store_id", "S001", "quantity", 24)), "ERP",
                "store_inventory", query, AS_OF, QUERIED, false);
    }

    @Test
    @DisplayName("没有够分的资料、查到了数据：依据数据回答，带数据出处（来源、条件、截至时间、查询时间）")
    void answersFromDataAlone() {
        data.result = inventory();

        var answer = ask(List.of(), 0, "还剩 24 瓶[1]。");

        assertThat(answer.refused()).isFalse();
        assertThat(answer.citations()).isEmpty();
        assertThat(answer.dataCitations()).containsExactly(new DataCitation(1, TOOL, "ERP", "store_inventory",
                "store_id=S001，sku=A1001", AS_OF, QUERIED, false));
        assertThat(data.queries).containsExactly(TOOL + " {store_id=S001}");
        assertThat(selections.getFirst()).contains(TOOL + "（库存查询）：按站点查库存").contains("S001：一店")
                .endsWith("问题：一店冷柜饮料还剩多少");
        assertThat(prompts.getFirst()).contains(EvidencePrompt.DATA_BEGIN)
                .contains("[1] 数据来源：ERP / store_inventory；条件：store_id=S001，sku=A1001")
                .contains("quantity=24");
    }

    @Test
    @DisplayName("资料和数据都有：数据编号接着资料往下排，回答分别引用两者")
    void citesDocumentsAndDataSideBySide() {
        data.result = inventory();

        var answer = ask(List.of(SHELF), 0.9, "每天盘点两次[1]，现在还剩 24 瓶[2]。");

        assertThat(answer.citations()).singleElement()
                .satisfies(citation -> assertThat(citation.documentName()).isEqualTo("商品陈列.txt"));
        assertThat(answer.dataCitations()).singleElement().satisfies(citation -> assertThat(citation.index()).isEqualTo(2));
        assertThat(prompts.getFirst()).contains("[1] 来源：商品陈列.txt").contains("[2] 数据来源：ERP");
    }

    @Test
    @DisplayName("回答没有引用数据的编号：不挂数据出处")
    void noDataCitationWhenNotCited() {
        data.result = inventory();

        var answer = ask(List.of(SHELF), 0.9, "每天盘点两次[1]。");

        assertThat(answer.citations()).hasSize(1);
        assertThat(answer.dataCitations()).isEmpty();
    }

    @Test
    @DisplayName("数据超出范围被拒、资料也没有答案：按“无权查看”拒答，不调用回答模型，没有任何数据")
    void deniedWithoutDocumentsRefuses() {
        data.result = DataResult.denied("无权查看所请求的站点的数据");

        var answer = ask(List.of(), 0, "不该被调用");

        assertThat(answer.refused()).isTrue();
        assertThat(answer.refusalReason()).isEqualTo(RefusalReason.DATA_DENIED);
        assertThat(answer.text()).isEqualTo("无权查看所请求的站点的数据");
        assertThat(answer.dataCitations()).isEmpty();
        assertThat(prompts).isEmpty();
    }

    @Test
    @DisplayName("数据查询失败、资料也没有答案：按“暂时查不到”拒答，不编数据")
    void failureWithoutDocumentsRefuses() {
        data.result = DataResult.failed("数据查询暂时不可用，暂时查不到。");

        var answer = ask(List.of(), 0, "不该被调用");

        assertThat(answer.refusalReason()).isEqualTo(RefusalReason.DATA_UNAVAILABLE);
        assertThat(answer.text()).isEqualTo("数据查询暂时不可用，暂时查不到。");
        assertThat(prompts).isEmpty();
    }

    @Test
    @DisplayName("数据查询失败、资料答得上来：照常回答资料部分，末尾说明数据暂时查不到；提示词里没有数据")
    void failureWithDocumentsAddsNotice() {
        data.result = DataResult.failed("数据查询暂时不可用，暂时查不到。");

        var answer = ask(List.of(SHELF), 0.9, "每天盘点两次[1]。");

        assertThat(answer.refused()).isFalse();
        assertThat(answer.text()).isEqualTo("每天盘点两次[1]。\n\n（数据部分：数据查询暂时不可用，暂时查不到。）");
        assertThat(answer.dataCitations()).isEmpty();
        assertThat(prompts.getFirst()).doesNotContain(EvidencePrompt.DATA_BEGIN);
    }

    @Test
    @DisplayName("模型选了不在可用清单里的工具：不调用，只依据资料回答")
    void unavailableToolIsNotCalled() {
        choose = user -> Optional.of(new ToolChoice("delete_inventory", Map.of("store_id", "S001")));

        var answer = ask(List.of(SHELF), 0.9, "每天盘点两次[1]。");

        assertThat(data.queries).isEmpty();
        assertThat(answer.text()).isEqualTo("每天盘点两次[1]。");
    }

    @Test
    @DisplayName("模型判断不用查数据，或选工具时模型不可用：不查数据，按原来的方式回答")
    void noToolChosen() {
        choose = user -> Optional.empty();
        assertThat(ask(List.of(SHELF), 0.9, "每天盘点两次[1]。").refused()).isFalse();

        choose = user -> {
            throw new ModelUnavailableException("模型不可用", null);
        };
        assertThat(ask(List.of(), 0, "x").refusalReason()).isEqualTo(RefusalReason.NO_EVIDENCE);
        assertThat(data.queries).isEmpty();
    }

    @Test
    @DisplayName("没有可用的数据工具：不调用选工具的模型")
    void noCatalogSkipsSelection() {
        data.catalog = DataCatalog.empty();

        ask(List.of(SHELF), 0.9, "每天盘点两次[1]。");

        assertThat(selections).isEmpty();
        assertThat(data.queries).isEmpty();
    }
}
