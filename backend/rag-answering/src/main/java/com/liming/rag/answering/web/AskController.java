package com.liming.rag.answering.web;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.liming.rag.ai.ModelCallException;
import com.liming.rag.answering.api.Answer;
import com.liming.rag.answering.application.AnswerService;
import com.liming.rag.identity.api.CurrentUser;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 问答接口。要登录，租户和提问人从登录会话取；{@code knowledgeBaseIds} 可不传，只会在提问人是成员的知识库里检索。
 */
@RestController
@RequestMapping("/api/ask")
@SaCheckLogin
class AskController {

    private static final long STREAM_TIMEOUT_MILLIS = Duration.ofMinutes(2).toMillis();
    /** 每次流式问答一个虚拟线程，阻塞等待模型输出不占平台线程。 */
    private static final ExecutorService STREAMS = Executors.newVirtualThreadPerTaskExecutor();

    private final AnswerService answers;
    private final CurrentUser currentUser;

    AskController(AnswerService answers, CurrentUser currentUser) {
        this.answers = answers;
        this.currentUser = currentUser;
    }

    record AskRequest(String question, List<Long> knowledgeBaseIds) {
    }

    @PostMapping
    Answer ask(@RequestBody AskRequest request) {
        return answers.ask(currentUser.tenantId(), currentUser.userId(), request.knowledgeBaseIds(), request.question());
    }

    /**
     * 流式问答（SSE）。事件依次是若干 {@code delta}（{"text": 片段}）和一个 {@code done}（完整的 Answer）；
     * {@code done} 是最终结果，拒答时客户端要用它替换已显示的文字。出错时发 {@code error}（{"status", "detail"}）。
     */
    @PostMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter stream(@RequestBody AskRequest request) {
        // 登录信息只在请求线程里能取到，先取出来再交给后台线程。
        var tenantId = currentUser.tenantId();
        var userId = currentUser.userId();
        var emitter = new SseEmitter(STREAM_TIMEOUT_MILLIS);
        STREAMS.execute(() -> {
            try {
                var answer = answers.ask(tenantId, userId, request.knowledgeBaseIds(), request.question(),
                        delta -> send(emitter, "delta", Map.of("text", delta)));
                send(emitter, "done", answer);
                emitter.complete();
            } catch (IllegalArgumentException e) {
                finishWithError(emitter, 400, e.getMessage());
            } catch (ModelCallException e) {
                finishWithError(emitter, 503, e.getMessage());
            } catch (UncheckedIOException e) {
                // 客户端已断开，停止生成即可。
                emitter.completeWithError(e);
            }
        });
        return emitter;
    }

    private static void finishWithError(SseEmitter emitter, int status, String detail) {
        send(emitter, "error", Map.of("status", status, "detail", detail));
        emitter.complete();
    }

    private static void send(SseEmitter emitter, String event, Object data) {
        try {
            emitter.send(SseEmitter.event().name(event).data(data, MediaType.APPLICATION_JSON));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
