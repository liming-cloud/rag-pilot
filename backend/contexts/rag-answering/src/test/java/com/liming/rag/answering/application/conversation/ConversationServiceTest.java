package com.liming.rag.answering.application.conversation;

import static com.liming.rag.answering.application.InMemoryAnswering.TENANT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.liming.rag.answering.application.AnsweringException;
import com.liming.rag.answering.application.InMemoryAnswering;
import com.liming.rag.answering.application.TestSettings;
import com.liming.rag.answering.application.ask.AskResult;
import com.liming.rag.answering.application.review.AnswerLogService;
import com.liming.rag.answering.domain.Citation;
import com.liming.rag.answering.domain.Conversation;
import com.liming.rag.answering.domain.ConversationTurn;
import com.liming.rag.knowledge.api.DocumentFreshness;
import com.liming.rag.shared.BusinessException;
import com.liming.rag.shared.UserId;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.function.Supplier;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 员工端会话（#107、D-036）：只能操作自己的会话，知识库按当前授权处理，轮次接在主线上（#135）。 */
class ConversationServiceTest {

    private static final UserId OWNER = new UserId(7);
    private static final UserId OTHER = new UserId(8);
    private static final long STORE = 1;
    private static final long GOODS = 2;
    private static final long MANAGER = 3;

    private final InMemoryAnswering fixture = new InMemoryAnswering();
    private final AnswerLogService log = new AnswerLogService(fixture.records, fixture.reviews, fixture.users,
            fixture.access, TestSettings.RETAIL, event -> { }, Clock.systemUTC());
    private final ConversationService service = new ConversationService(fixture.conversations, fixture.records,
            fixture.access, fixture.access, log, Clock.systemUTC());

    {
        fixture.access.grant(7, STORE, "门店规范").grant(7, GOODS, "商品知识").grant(8, STORE, "门店规范");
        fixture.access.names.put(MANAGER, "店长手册");
    }

    @Test
    @DisplayName("新建：点名无权限的库被忽略；没点名或全被忽略时选中全部已授权的库；一个都没有时是 INVALID")
    void createsWithAccessibleKnowledgeBases() {
        assertThat(service.create(TENANT, OWNER, null).knowledgeBaseIds()).containsExactly(STORE, GOODS);
        assertThat(service.create(TENANT, OWNER, List.of(GOODS, MANAGER)).knowledgeBaseIds()).containsExactly(GOODS);
        assertThat(service.create(TENANT, OWNER, List.of(MANAGER)).knowledgeBaseIds()).containsExactly(STORE, GOODS);
        assertReason(() -> service.create(TENANT, new UserId(99), null), AnsweringException.Reason.INVALID);
        assertThat(service.list(TENANT, OWNER)).hasSize(3);
        assertThat(service.list(TENANT, OTHER)).isEmpty();
    }

    @Test
    @DisplayName("别人的会话看不到、改不了、删不掉，一律按不存在处理")
    void othersConversationsAreNotFound() {
        long id = service.create(TENANT, OWNER, null).id();

        assertReason(() -> service.detail(TENANT, OTHER, id), AnsweringException.Reason.NOT_FOUND);
        assertReason(() -> service.update(TENANT, OTHER, id, "偷改", null), AnsweringException.Reason.NOT_FOUND);
        assertReason(() -> service.delete(TENANT, OTHER, id), AnsweringException.Reason.NOT_FOUND);
        assertReason(() -> service.detail(TENANT, OWNER, 404), AnsweringException.Reason.NOT_FOUND);
        assertThat(fixture.conversations.rows).containsKey(id);
    }

    @Test
    @DisplayName("重命名、改选知识库：改选只保留已授权的库，一个都不剩或标题不合格是 INVALID")
    void updatesTitleAndKnowledgeBases() {
        long id = service.create(TENANT, OWNER, null).id();

        var updated = service.update(TENANT, OWNER, id, "  补货流程 ", List.of(GOODS, MANAGER));
        assertThat(updated.title()).isEqualTo("补货流程");
        assertThat(updated.knowledgeBaseIds()).containsExactly(GOODS);

        assertReason(() -> service.update(TENANT, OWNER, id, null, List.of(MANAGER)), AnsweringException.Reason.INVALID);
        assertReason(() -> service.update(TENANT, OWNER, id, " ", null), AnsweringException.Reason.INVALID);
    }

    @Test
    @DisplayName("会话被别的操作改过时，改名返回 CONFLICT")
    void conflictsWhenChangedConcurrently() {
        long id = service.create(TENANT, OWNER, null).id();
        fixture.conversations.staleUpdates = 1;

        assertReason(() -> service.update(TENANT, OWNER, id, "补货流程", null), AnsweringException.Reason.CONFLICT);
        assertThat(service.detail(TENANT, OWNER, id).title()).isEqualTo("新对话");
    }

    @Test
    @DisplayName("一轮接一轮：回答记录记下这一轮实际检索的库；第一次提问时标题用问题截断；主线按接上的先后排列")
    void recordsTurnsOnMainLine() {
        long id = service.create(TENANT, OWNER, null).id();
        var answer = AskResult.answered("青柚在 A 区[1]。", List.of(citation()), 0.9);

        var first = service.prepareTurn(TENANT, OWNER, id, null);
        assertThat(first.firstTurn()).isTrue();
        assertThat(first.knowledgeBaseIds()).containsExactly(STORE, GOODS);
        service.recordTurn(newTurn(id, 11, "青柚在哪？", first.knowledgeBaseIds(), null),
                ConversationTurn.Status.ANSWERED, answer);
        service.recordTurn(newTurn(id, 12, "补货先做什么？", first.knowledgeBaseIds(), null),
                ConversationTurn.Status.STOPPED, null);

        var detail = service.detail(TENANT, OWNER, id);
        assertThat(detail.title()).isEqualTo("青柚在哪？");
        assertThat(detail.turns()).extracting(ConversationViews.Turn::question).containsExactly("青柚在哪？", "补货先做什么？");
        assertThat(detail.turns().get(1).answer()).as("停止时没有回答").isNull();
        assertThat(fixture.records.rows.values()).singleElement()
                .satisfies(row -> assertThat(row.knowledgeBaseIds()).containsExactly(STORE, GOODS));
        var next = service.prepareTurn(TENANT, OWNER, id, null);
        assertThat(next.firstTurn()).isFalse();
        assertThat(next.history()).extracting(ConversationHistory.Entry::question)
                .containsExactly("青柚在哪？", "补货先做什么？");
    }

    @Test
    @DisplayName("重新生成：只能替换主线最后一轮；替换时历史不含被替换的那一轮，新的一轮接在它的上一轮之后")
    void replacesOnlyTheLastTurn() {
        long id = service.create(TENANT, OWNER, null).id();
        service.recordTurn(newTurn(id, 11, "青柚在哪？", List.of(STORE), null), ConversationTurn.Status.ANSWERED, null);
        service.recordTurn(newTurn(id, 12, "补货先做什么？", List.of(STORE), null), ConversationTurn.Status.ANSWERED, null);

        assertReason(() -> service.prepareTurn(TENANT, OWNER, id, 11L), AnsweringException.Reason.INVALID);
        var replacing = service.prepareTurn(TENANT, OWNER, id, 12L);
        assertThat(replacing.history()).extracting(ConversationHistory.Entry::question).containsExactly("青柚在哪？");
        service.recordTurn(newTurn(id, 13, "补货前要移出什么？", List.of(STORE), 12L), ConversationTurn.Status.ANSWERED, null);

        assertThat(service.detail(TENANT, OWNER, id).turns()).extracting(ConversationViews.Turn::question)
                .containsExactly("青柚在哪？", "补货前要移出什么？");
        assertThat(fixture.conversations.superseded).containsExactly(12L);
        assertReason(() -> service.recordTurn(newTurn(id, 14, "再改", List.of(STORE), 12L),
                ConversationTurn.Status.ANSWERED, null), AnsweringException.Reason.INVALID);
    }

    @Test
    @DisplayName("这一轮没接上（会话刚被改过）时返回 CONFLICT，回答记录和轮次都不留")
    void conflictingTurnLeavesNothing() {
        long id = service.create(TENANT, OWNER, null).id();
        fixture.conversations.staleUpdates = 1;

        assertReason(() -> service.recordTurn(newTurn(id, 11, "青柚在哪？", List.of(STORE), null),
                ConversationTurn.Status.ANSWERED, AskResult.answered("A 区[1]。", List.of(citation()), 0.9)),
                AnsweringException.Reason.CONFLICT);
        assertThat(fixture.conversations.turns).isEmpty();
    }

    @Test
    @DisplayName("授权收回后：知识库标为无权限，历史引用不再给原文，这一轮实际检索的库去掉它")
    void followsCurrentAccess() {
        long id = service.create(TENANT, OWNER, null).id();
        fixture.access.excerpts.put(100L, "青柚陈列在 A 区。");
        service.recordTurn(newTurn(id, 11, "青柚在哪？", List.of(STORE, GOODS), null),
                ConversationTurn.Status.ANSWERED, AskResult.answered("A 区[1]。", List.of(citation()), 0.9));
        assertThat(service.detail(TENANT, OWNER, id).turns().getFirst().answer().citations().getFirst().excerpt())
                .isEqualTo("青柚陈列在 A 区。");

        fixture.access.revoke(7, GOODS);
        fixture.access.excerpts.clear();

        var detail = service.detail(TENANT, OWNER, id);
        assertThat(detail.knowledgeBases()).extracting(ConversationViews.KnowledgeBaseOption::accessible)
                .containsExactly(true, false);
        assertThat(detail.turns().getFirst().answer().citations().getFirst().excerpt()).isNull();
        assertThat(service.prepareTurn(TENANT, OWNER, id, null).knowledgeBaseIds()).containsExactly(STORE);
    }

    @Test
    @DisplayName("历史引用带上资料的更新时间和状态：正常、已过失效日期、文档已不在（被新版本替换或删除）")
    void citationsShowDocumentFreshness() {
        long id = service.create(TENANT, OWNER, null).id();
        var uploaded = OffsetDateTime.parse("2026-10-26T09:00:00+08:00");
        service.recordTurn(newTurn(id, 11, "青柚在哪？", List.of(STORE), null), ConversationTurn.Status.ANSWERED,
                AskResult.answered("A 区[1]。", List.of(citation()), 0.9));
        Supplier<Citation> shown = () -> service.detail(TENANT, OWNER, id).turns().getFirst()
                .answer().citations().getFirst();

        fixture.access.documents.put(50L, new DocumentFreshness(uploaded, false));
        assertThat(shown.get().documentState()).isEqualTo(Citation.DocumentState.CURRENT);
        assertThat(shown.get().documentUpdatedAt()).isEqualTo(uploaded);

        fixture.access.documents.put(50L, new DocumentFreshness(uploaded, true));
        assertThat(shown.get().documentState()).isEqualTo(Citation.DocumentState.EXPIRED);

        fixture.access.documents.clear();
        assertThat(shown.get().documentState()).isEqualTo(Citation.DocumentState.REPLACED);
        assertThat(shown.get().documentUpdatedAt()).isNull();
    }

    @Test
    @DisplayName("后台生成的标题只替换问题截断；和别的修改撞上时重新读一次再试；会话已删时什么也不做")
    void appliesGeneratedTitle() {
        long id = service.create(TENANT, OWNER, null).id();
        service.recordTurn(newTurn(id, 11, "冷柜温度要多少？", List.of(STORE), null), ConversationTurn.Status.ANSWERED, null);
        fixture.conversations.staleUpdates = 1;

        service.applyGeneratedTitle(TENANT, id, "冷柜温度");
        assertThat(service.detail(TENANT, OWNER, id).title()).isEqualTo("冷柜温度");
        assertThat(fixture.conversations.rows.get(id).titleSource()).isEqualTo(Conversation.TitleSource.MODEL);

        service.delete(TENANT, OWNER, id);
        service.applyGeneratedTitle(TENANT, id, "不会出现");
        assertThat(fixture.conversations.rows).doesNotContainKey(id);
    }

    @Test
    @DisplayName("删除会话只影响员工端，回答记录照常保留")
    void deleteKeepsAnswerRecords() {
        long id = service.create(TENANT, OWNER, null).id();
        service.recordTurn(newTurn(id, 11, "青柚在哪？", List.of(STORE), null),
                ConversationTurn.Status.ANSWERED, AskResult.answered("A 区[1]。", List.of(citation()), 0.9));

        service.delete(TENANT, OWNER, id);

        assertThat(service.list(TENANT, OWNER)).isEmpty();
        assertThat(fixture.records.rows).hasSize(1);
    }

    private static Citation citation() {
        return new Citation(1, 100, 50, "陈列规范", "陈列规范 > 饮料", null);
    }

    /** 按错误码断言：规则违规可能来自 application 的 AnsweringException，也可能来自领域对象（D-067），错误码是同一套。 */
    private static void assertReason(ThrowingCallable call, AnsweringException.Reason reason) {
        assertThatThrownBy(call).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.code()).isEqualTo("ANSWERING_" + reason.name()));
    }

    private static ConversationService.NewTurn newTurn(long conversationId, long turnId, String question,
            List<Long> knowledgeBaseIds, Long replacesTurnId) {
        return new ConversationService.NewTurn(TENANT, OWNER, conversationId, turnId, question, knowledgeBaseIds,
                replacesTurnId);
    }
}
