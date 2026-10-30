package com.liming.rag.answering.application;

import com.liming.rag.answering.api.Answer;
import com.liming.rag.answering.domain.ConversationTurn;
import java.time.Instant;
import java.util.List;

/** 会话接口返回的视图，字段与前端 features/chat/api.ts 的类型一致。 */
public final class ConversationViews {

    private ConversationViews() {
    }

    /** 侧栏里的一个会话。 */
    public record Summary(long id, String title, List<Long> knowledgeBaseIds, Instant lastAskedAt) {
    }

    /** 会话选中的一个知识库。accessible 为 false：授权已收回，后续提问不再检索它。 */
    public record KnowledgeBaseOption(long id, String name, boolean accessible) {
    }

    /**
     * 主线上的一轮。answer 的引用原文按当前授权补上，无权查看时 excerpt 为 null。
     *
     * @param answer 停止时没留下可核对的内容、或出错时为 null
     */
    public record Turn(long id, String question, ConversationTurn.Status status, Answer answer,
            List<Long> knowledgeBaseIds, Instant askedAt) {
    }

    public record Detail(long id, String title, Instant lastAskedAt, List<KnowledgeBaseOption> knowledgeBases,
            List<Turn> turns) {
    }
}
