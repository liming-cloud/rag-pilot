package com.liming.rag.answering.application.conversation;

import com.liming.rag.answering.application.AnsweringProperties;
import com.liming.rag.answering.application.ModelUnavailableException;
import com.liming.rag.answering.application.ask.AnswerPrompt;
import com.liming.rag.answering.application.ask.AnswerService;
import com.liming.rag.answering.application.ask.AskResult;
import com.liming.rag.answering.domain.ConversationTurn;
import com.liming.rag.answering.domain.Question;
import com.liming.rag.answering.domain.RefusalReason;
import com.liming.rag.shared.TenantId;
import com.liming.rag.shared.UserId;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.AsyncTaskExecutor;
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

    private static final Logger log = LoggerFactory.getLogger(ConversationAskService.class);

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

    private static final int SMALL_TALK_MAX_TOKENS = 48;
    private static final int TITLE_MAX_TOKENS = 32;
    private static final Pattern DIGITS = Pattern.compile("\\d");

    private final ConversationService conversations;
    private final AskRateLimiter limiter;
    private final AnswerService answers;
    private final LightReplies light;
    private final SearchPlanner planner;
    private final TurnStops stops;
    private final AnsweringProperties.Conversation settings;
    /** 标题在后台生成，不占回答的时间；停机时等它写完（#135）。 */
    private final AsyncTaskExecutor background;

    ConversationAskService(ConversationService conversations, AnswerService answers, LightReplies light,
            FollowUpPlanning planning, TurnStops stops, AskRateLimiter limiter, AnsweringProperties properties,
            @Qualifier("applicationTaskExecutor") AsyncTaskExecutor background) {
        this.conversations = conversations;
        this.limiter = limiter;
        this.answers = answers;
        this.light = light;
        this.stops = stops;
        this.settings = properties.conversation();
        this.background = background;
        this.planner = new SearchPlanner(planning, settings.planningTimeout(), background);
    }

    /** 生成过程中的事件，交给流式接口转成 SSE。 */
    public record Events(Consumer<String> onStatus, Consumer<String> onDelta) {
    }

    /**
     * @throws AnsweringException 问题为空或太长、会话不存在或不是自己的、要替换的不是最后一轮
     */
    public PendingTurn start(TenantId tenantId, UserId userId, long conversationId, String question, @Nullable Long replacesTurnId) {
        var text = AnswerService.question(question);
        var context = conversations.prepareTurn(tenantId, userId, conversationId, replacesTurnId);
        // 问题合规、会话是自己的之后才计数，超过频率上限时不调用模型（#145）。
        limiter.acquire(tenantId, userId);
        long turnId = conversations.nextTurnId();
        var stop = stops.register(conversationId, turnId);
        return new PendingTurn(tenantId, userId, conversationId, turnId, text, replacesTurnId, context, stop);
    }

    /**
     * 生成并保存这一轮，返回最终结果。模型不可用时存为 FAILED 再抛出。
     *
     * @throws ModelUnavailableException 模型服务不可用
     */
    public ConversationViews.Turn run(PendingTurn pending, Events events) {
        try {
            AskResult answer;
            try {
                answer = answer(pending, events);
            } catch (ModelUnavailableException e) {
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
                background.execute(() -> generateTitle(pending));
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

    private AskResult answer(PendingTurn pending, Events events) {
        var context = pending.context();
        if (context.knowledgeBaseIds().isEmpty()) {
            return answers.refusal(RefusalReason.NO_EVIDENCE, null);
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
            return AskResult.smallTalk(reply);
        }
        var request = new AnswerService.AskRequest(pending.tenantId(), pending.userId(), context.knowledgeBaseIds(),
                pending.question(), plan.queries(), generationHistory);
        return answers.ask(request, events.onDelta(), events.onStatus(), pending.stop());
    }

    /** 寒暄用轻量模型回一句；太长、带数字或模型出错时用固定的一句，保证不含事实内容。 */
    private String smallTalk(String question) {
        try {
            return acceptSmallTalk(light.reply(SMALL_TALK_SYSTEM, question, SMALL_TALK_MAX_TOKENS));
        } catch (RuntimeException e) {
            log.warn("event=small_talk_fallback error={}", e.getClass().getSimpleName());
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

    private void generateTitle(PendingTurn pending) {
        try {
            acceptTitle(light.reply(TITLE_SYSTEM, pending.question(), TITLE_MAX_TOKENS)).ifPresent(title ->
                    conversations.applyGeneratedTitle(pending.tenantId(), pending.conversationId(), title));
        } catch (RuntimeException e) {
            // 标题生成失败不影响使用，保留问题截断。
            log.warn("event=title_generation_failed conversation={} error={}", pending.conversationId(),
                    e.getClass().getSimpleName());
        }
    }

    private ConversationViews.Turn record(PendingTurn pending, ConversationTurn.Status status, AskResult answer) {
        return conversations.recordTurn(new ConversationService.NewTurn(pending.tenantId(), pending.userId(),
                pending.conversationId(), pending.turnId(), pending.question(), pending.context().knowledgeBaseIds(),
                pending.replacesTurnId()), status, answer);
    }
}
