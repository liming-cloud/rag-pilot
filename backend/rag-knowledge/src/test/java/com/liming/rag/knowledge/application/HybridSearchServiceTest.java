package com.liming.rag.knowledge.application;

import static com.liming.rag.knowledge.application.InMemoryKnowledge.TENANT;
import static org.assertj.core.api.Assertions.assertThat;

import com.liming.rag.knowledge.api.SearchHit;
import com.liming.rag.knowledge.api.SearchQuery;
import com.liming.rag.shared.UserId;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 混合检索：向量和关键词两路召回、RRF 融合；授权范围由检索服务自己向 identity 查（D-038），只查当天有效的文档（D-046）。 */
class HybridSearchServiceTest {

    private static final UserId STAFF = new UserId(2);

    private final InMemoryKnowledge fixture = new InMemoryKnowledge();
    private final List<List<String>> embedded = new ArrayList<>();
    private final HybridSearchService service = new HybridSearchService(fixture.index, texts -> {
        embedded.add(texts);
        return texts.stream().map(text -> new float[] {1}).toList();
    }, fixture.users, fixture.calendar);

    {
        fixture.users.user(2, "店员", 40L, 41L);
        for (long id = 1; id <= 3; id++) {
            fixture.index.hits.put(id, new SearchHit(id, 10 + id, "doc" + id, "doc" + id, "text" + id, 0));
        }
    }

    @Test
    @DisplayName("空白问题不检索，也不调用嵌入模型")
    void skipsBlankQuestions() {
        assertThat(service.search(new SearchQuery(TENANT, STAFF, List.of(), "  ", 10))).isEmpty();
        assertThat(embedded).isEmpty();
    }

    @Test
    @DisplayName("两路召回都在提问人本人和其角色的授权范围、今天有效的文档里查；问题去掉首尾空格")
    void searchesWithinGranteeAndToday() {
        service.search(new SearchQuery(TENANT, STAFF, List.of(7L), "  交班  ", 10));

        assertThat(embedded).containsExactly(List.of("交班"));
        assertThat(fixture.index.grantee).isEqualTo(new Grantee(TENANT, STAFF, List.of(40L, 41L)));
        assertThat(fixture.index.today).isEqualTo(InMemoryKnowledge.TODAY);
        assertThat(fixture.index.keywordTexts).containsExactly("交班");
        assertThat(fixture.index.limit).isEqualTo(HybridSearchService.CANDIDATES_PER_ROUTE);
    }

    @Test
    @DisplayName("两路都命中的分块排在前面；分数是融合分；已被删掉、取不到详情的分块不返回")
    void fusesBothRoutes() {
        fixture.index.semantic = List.of(1L, 2L, 99L);
        fixture.index.keyword = List.of(2L, 3L);

        var hits = service.search(new SearchQuery(TENANT, STAFF, List.of(), "交班", 10));

        assertThat(hits).extracting(SearchHit::chunkId).startsWith(2L).containsOnly(1L, 2L, 3L);
        assertThat(hits).allSatisfy(hit -> assertThat(hit.score()).isPositive());
        assertThat(hits.getFirst().documentName()).isEqualTo("doc2");
    }

    @Test
    @DisplayName("返回条数限制在 1 到 50 之间")
    void clampsLimit() {
        fixture.index.semantic = List.of(1L, 2L, 3L);

        assertThat(service.search(new SearchQuery(TENANT, STAFF, List.of(), "交班", 0))).hasSize(1);
        assertThat(service.search(new SearchQuery(TENANT, STAFF, List.of(), "交班", 500))).hasSize(3);
    }

    @Test
    @DisplayName("引用原文按当前授权补：没有分块时不查；有授权的才返回原文")
    void readsExcerptsWithinGrants() {
        assertThat(service.excerpts(TENANT, STAFF, Set.of())).isEmpty();
        assertThat(fixture.index.grantee).isNull();

        fixture.index.contents.put(1L, "原文一");
        assertThat(service.excerpts(TENANT, STAFF, Set.of(1L, 2L))).containsOnlyKeys(1L).containsValue("原文一");
        assertThat(fixture.index.grantee).isEqualTo(new Grantee(TENANT, STAFF, List.of(40L, 41L)));
    }
}
