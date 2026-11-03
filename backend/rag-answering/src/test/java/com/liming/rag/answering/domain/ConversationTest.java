package com.liming.rag.answering.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.liming.rag.shared.TenantId;
import com.liming.rag.shared.UserId;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ConversationTest {

    private static final TenantId TENANT = new TenantId(1);
    private static final UserId OWNER = new UserId(7);
    private static final UserId OTHER = new UserId(8);

    private static Conversation started() {
        return Conversation.start(TENANT, OWNER, List.of(3L, 4L));
    }

    @Test
    @DisplayName("新会话叫“新对话”，必须至少选一个知识库")
    void startsWithKnowledgeBases() {
        var conversation = started();

        assertThat(conversation.title()).isEqualTo("新对话");
        assertThat(conversation.titleSource()).isEqualTo(Conversation.TitleSource.NEW);
        assertThat(conversation.knowledgeBaseIds()).containsExactly(3L, 4L);
        assertThatThrownBy(() -> Conversation.start(TENANT, OWNER, List.of())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("只有本人能操作；别人操作按不存在处理")
    void onlyOwnerCanChange() {
        var conversation = started();

        assertThatThrownBy(() -> conversation.requireOwner(OTHER)).isInstanceOf(Conversation.NotOwnerException.class);
        assertThatThrownBy(() -> conversation.rename(OTHER, "偷改")).isInstanceOf(Conversation.NotOwnerException.class);
        assertThatThrownBy(() -> conversation.selectKnowledgeBases(OTHER, List.of(3L)))
                .isInstanceOf(Conversation.NotOwnerException.class);
    }

    @Test
    @DisplayName("重命名去掉首尾空白，不能为空、不超过 40 字；改过后不再自动生成标题")
    void renames() {
        var conversation = started();

        conversation.rename(OWNER, "  冷柜温度  ");
        conversation.titleGenerated("模型起的标题");

        assertThat(conversation.title()).isEqualTo("冷柜温度");
        assertThat(conversation.titleSource()).isEqualTo(Conversation.TitleSource.USER);
        assertThatThrownBy(() -> conversation.rename(OWNER, "  ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> conversation.rename(OWNER, "长".repeat(41))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("第一次提问后标题先用问题截断，模型生成的标题再替换它")
    void titleFollowsFirstQuestion() {
        var conversation = started();
        var askedAt = Instant.parse("2026-10-30T02:00:00Z");

        conversation.asked("临期商品怎么处理？保质期剩余多少要贴黄色标签，又什么时候下架？", askedAt);
        assertThat(conversation.title()).isEqualTo("临期商品怎么处理？保质期剩余多少要贴黄色…");
        assertThat(conversation.titleSource()).isEqualTo(Conversation.TitleSource.QUESTION);
        assertThat(conversation.lastAskedAt()).isEqualTo(askedAt);

        conversation.asked("那报损呢？", askedAt.plusSeconds(60));
        assertThat(conversation.title()).startsWith("临期商品怎么处理");

        conversation.titleGenerated("  临期商品处理\n");
        assertThat(conversation.title()).isEqualTo("临期商品处理");
        assertThat(conversation.titleSource()).isEqualTo(Conversation.TitleSource.MODEL);
    }

    @Test
    @DisplayName("模型给的标题是空的或太长时不采用")
    void ignoresBadGeneratedTitle() {
        var conversation = started();
        conversation.asked("冷柜温度要多少？", Instant.now());

        conversation.titleGenerated("   ");
        conversation.titleGenerated("长".repeat(41));

        assertThat(conversation.title()).isEqualTo("冷柜温度要多少？");
        assertThat(conversation.titleSource()).isEqualTo(Conversation.TitleSource.QUESTION);
    }

    @Test
    @DisplayName("改选知识库：不能一个都不选，去掉重复")
    void selectsKnowledgeBases() {
        var conversation = started();

        conversation.selectKnowledgeBases(OWNER, List.of(4L, 4L, 5L));

        assertThat(conversation.knowledgeBaseIds()).containsExactly(4L, 5L);
        assertThatThrownBy(() -> conversation.selectKnowledgeBases(OWNER, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("接着问时接在主线最后一轮之后；替换时接在被替换那一轮的上一轮之后（#135）")
    void appendsTurnsToTheMainLine() {
        var conversation = started();
        var at = Instant.parse("2026-11-03T08:00:00Z");

        assertThat(conversation.appendTurn(10, null, "青柚在哪？", at)).isNull();
        assertThat(conversation.appendTurn(11, null, "补货先做什么？", at)).isEqualTo(10L);
        assertThat(conversation.appendTurn(12, 11L, "补货前要移出什么？", at)).isEqualTo(10L);

        assertThat(conversation.tail()).isEqualTo(new Conversation.MainLineTail(12, 10L));
        assertThat(conversation.title()).as("第一轮的问题当标题").isEqualTo("青柚在哪？");
    }

    @Test
    @DisplayName("只能重新生成或修改主线最后一轮；还没提问时不能替换（#135）")
    void replacesOnlyTheLastTurn() {
        var conversation = started();

        assertThatThrownBy(() -> conversation.checkReplaceable(10L)).isInstanceOf(IllegalArgumentException.class);
        conversation.appendTurn(10, null, "青柚在哪？", Instant.now());
        conversation.appendTurn(11, null, "补货先做什么？", Instant.now());

        assertThatThrownBy(() -> conversation.appendTurn(12, 10L, "改问", Instant.now()))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("只能重新生成或修改最后一轮");
        assertThat(conversation.tail()).as("被拒绝时主线不变").isEqualTo(new Conversation.MainLineTail(11, 10L));
        conversation.checkReplaceable(11L);
        conversation.checkReplaceable(null);
    }
}
