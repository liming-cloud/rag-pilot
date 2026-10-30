package com.liming.rag.answering.application;

import com.liming.rag.ai.ChatGateway;
import com.liming.rag.ai.ModelCallException;
import com.liming.rag.answering.api.Answer;
import com.liming.rag.answering.domain.ConversationTurn;
import com.liming.rag.shared.TenantId;
import com.liming.rag.shared.UserId;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 在会话里提问（#107、D-036）。分两步：{@link #start} 在请求线程里校验（会话是不是自己的、替换的是不是最后一轮），
 * 分配轮次 id、登记停止信号；{@link #run} 在后台线程里生成并保存，员工断开连接也照样做完。
 *
 * <p>有历史时先做检索规划（{@link SearchPlanner}），给出补全后的检索词，或判断为寒暄；寒暄用轻量模型回一句，
 * 不检索、不带引用。所选的库都已收回授权时直接拒答，不进检索——空的库列表在检索里表示“全部有权限的库”。
 * 第一次提问回答后，在后台用轻量模型生成标题。
 */
@Service
public class ConversationAskService {

    private static final Logger LOG = LoggerFactory.getLogger(ConversationAskService.class);

    static final String UNDERSTANDING_STATUS = "正在理解你的追问…";

    static final String SMALL_TALK_SYSTEM = """
            员工对你说的是寒暄、致谢或问候。用一句不超过 20 个字的话礼貌回应。
            不要提供任何事实、数字、规定或建议，不要提问。
            """;
    static final String SMALL_TALK_FALLBACK = "好的，有问题随时问我。";
    static final int MAX_SMALL_TALK_CHARS = 30;

    static final String TITLE_SYSTEM = """
            根据员工在对话里的第一个问题，起一个不超过 12 个字的标题，概括问的是什么。
            只输出标题本身，不要标点、引号和解释。
            """;
    static final int MAX_TITLE_CHARS = 16;

    private static final ChatGateway.Limits SMALL_TALK_LIMITS =
            new ChatGateway.Limits(48, List.of("\n"), ChatGateway.Tier.LIGHT);
    private static final ChatGateway.Limits TITLE_LIMITS =
            new ChatGateway.Limits(32, List.of("\n"), ChatGateway.Tier.LIGHT);
    private static final Pattern DIGITS = Pattern.compile("\\d");

    /** 标题在后台生成，不占回答的时间。 */
    private static final ExecutorService TITLES = Executors.newVirtualThreadPerTaskExecutor();

    private final ConversationService conversations;
    private final AnswerService answers;
    private final ChatGateway chat;
    private final SearchPlanner planner;
    private final TurnStops stops;
    private final AnsweringProperties.Conversation settings;

    ConversationAskService(ConversationService conversations, AnswerService answers, ChatGateway chat, TurnStops stops,
            AnsweringProperties properties) {
        this.conversations = conversations;
        this.answers = answers;
        this.chat = chat;
        this.stops = stops;
        this.settings = properties.conversation();
        this.planner = new SearchPlanner(chat, settings.planningTimeout());
    }

    /** 准备好的一轮：已校验、已分配 id、已登记停止信号。 */
    public record Pending(TenantId tenantId, UserId userId, long conversationId, long turnId, String question,
            Long replacesTurnId, ConversationService.TurnContext context, CompletableFuture<Void> stop) {
    }

    /** 生成过程中的事件，交给流式接口转成 SSE。 */
    public record Events(Consumer<String> onStatus, Consumer<String> onDelta) {
    }

    /**
     * @throws AnsweringException 问题为空或太长、会话不存在或不是自己的、要替换的不是最后一轮
     */
    public Pending start(TenantId tenantId, UserId userId, long conversationId, String question, Long replacesTurnId) {
        var text = question == null ? "" : question.strip();
        if (text.isEmpty() || text.length() > AnswerService.MAX_QUESTION_LENGTH) {
            throw ConversationService.invalid("问题不能为空，且不超过 " + AnswerService.MAX_QUESTION_LENGTH + " 个字");
        }
        var context = conversations.prepareTurn(tenantId, userId, conversationId, replacesTurnId);
        long turnId = conversations.nextTurnId();
        var stop = stops.register(conversationId, turnId);
        return new Pending(tenantId, userId, conversationId, turnId, text, replacesTurnId, context, stop);
    }

    /**
     * 生成并保存这一轮，返回最终结果。模型不可用时存为 FAILED 再抛出。
     *
     * @throws ModelCallException 模型服务不可用
     */
    public ConversationViews.Turn run(Pending pending, Events events) {
        try {
            Answer answer;
            try {
                answer = answer(pending, events);
            } catch (ModelCallException e) {
                record(pending, ConversationTurn.Status.FAILED, null);
                throw e;
            }
            // 只有员工点了停止（信号正常完成）才算停止。
            var stop = pending.stop();
            boolean stopped = stop.isDone() && !stop.isCompletedExceptionally();
            var status = stopped ? ConversationTurn.Status.STOPPED : ConversationTurn.Status.ANSWERED;
            // 停止后已生成的部分照样做引用校验：没有有效引用时不留回答（D-036）。
            var kept = stopped && answer.refused() ? null : answer;
            var turn = record(pending, status, kept);
            if (pending.context().firstTurn() && kept != null) {
                TITLES.execute(() -> generateTitle(pending));
            }
            return turn;
        } finally {
            stops.finished(pending.turnId());
        }
    }

    /** 停止自己会话里正在生成的一轮；已经结束时什么也不做。 */
    public void stop(TenantId tenantId, UserId userId, long conversationId, long turnId) {
        conversations.owned(tenantId, userId, conversationId);
        stops.stop(conversationId, turnId);
    }

    private Answer answer(Pending pending, Events events) {
        var context = pending.context();
        if (context.knowledgeBaseIds().isEmpty()) {
            return Answer.refused(Answer.RefusalReason.NO_EVIDENCE, null);
        }
        var plan = SearchPlanner.direct(pending.question());
        String generationHistory = "";
        if (!context.history().isEmpty()) {
            events.onStatus().accept(UNDERSTANDING_STATUS);
            var planningHistory = ConversationHistory.of(context.history(), settings.planningTurns(),
                    settings.historyMaxChars());
            plan = planner.plan(planningHistory.text(), pending.question());
            generationHistory = ConversationHistory.of(context.history(), settings.generationTurns(),
                    settings.historyMaxChars()).text();
        }
        if (plan.smallTalk()) {
            var reply = smallTalk(pending.question());
            events.onDelta().accept(reply);
            return Answer.smallTalk(reply);
        }
        var request = new AnswerService.AskRequest(pending.tenantId(), pending.userId(), context.knowledgeBaseIds(),
                pending.question(), plan.queries(), generationHistory);
        return answers.ask(request, events.onDelta(), events.onStatus(), pending.stop());
    }

    /** 寒暄用轻量模型回一句；太长、带数字或模型出错时用固定的一句，保证不含事实内容。 */
    private String smallTalk(String question) {
        try {
            return acceptSmallTalk(chat.complete(SMALL_TALK_SYSTEM, question, SMALL_TALK_LIMITS));
        } catch (RuntimeException e) {
            LOG.debug("寒暄回复生成失败，用固定回复", e);
            return SMALL_TALK_FALLBACK;
        }
    }

    /** 模型的寒暄回复只有短、不带数字时才用，否则用固定的一句。 */
    static String acceptSmallTalk(String reply) {
        var text = reply == null ? "" : reply.strip();
        if (text.isEmpty() || text.length() > MAX_SMALL_TALK_CHARS || DIGITS.matcher(text).find()
                || text.contains(AnswerPrompt.NO_ANSWER)) {
            return SMALL_TALK_FALLBACK;
        }
        return text;
    }

    /** 模型生成的标题：去掉引号和句末标点；空的、太长的、拒答标记或带引用标记的不采用。 */
    static Optional<String> acceptTitle(String generated) {
        var title = (generated == null ? "" : generated).strip()
                .replaceAll("^[\"“”'「」《》]+|[\"“”'「」《》。.!！?？]+$", "");
        if (title.isEmpty() || title.length() > MAX_TITLE_CHARS || title.contains(AnswerPrompt.NO_ANSWER)
                || title.contains("[")) {
            return Optional.empty();
        }
        return Optional.of(title);
    }

    private void generateTitle(Pending pending) {
        try {
            acceptTitle(chat.complete(TITLE_SYSTEM, pending.question(), TITLE_LIMITS)).ifPresent(title ->
                    conversations.applyGeneratedTitle(pending.tenantId(), pending.conversationId(), title));
        } catch (RuntimeException e) {
            // 标题生成失败不影响使用，保留问题截断。
            LOG.debug("会话标题生成失败", e);
        }
    }

    private ConversationViews.Turn record(Pending pending, ConversationTurn.Status status, Answer answer) {
        return conversations.recordTurn(pending.tenantId(), pending.userId(), pending.conversationId(),
                pending.turnId(), pending.question(), status, answer, pending.context().knowledgeBaseIds(),
                pending.replacesTurnId());
    }
}
