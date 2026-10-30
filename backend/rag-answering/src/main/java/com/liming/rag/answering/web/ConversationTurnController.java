package com.liming.rag.answering.web;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.liming.rag.answering.application.ConversationAskService;
import com.liming.rag.identity.api.CurrentUser;
import com.liming.rag.identity.api.Permissions;
import com.liming.rag.shared.BusinessException;
import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 在会话里提问和停止生成（#107、D-036）。提问前先校验（会话是不是自己的、替换的是不是最后一轮），不通过时直接返回
 * 404 / 400，不进事件流。
 */
@RestController
@RequestMapping("/api/conversations/{conversationId}/turns")
@SaCheckLogin
@SaCheckPermission(Permissions.CHAT_USE)
class ConversationTurnController {

    private static final Logger LOG = LoggerFactory.getLogger(ConversationTurnController.class);
    private static final long STREAM_TIMEOUT_MILLIS = Duration.ofMinutes(2).toMillis();
    /** 每次提问一个虚拟线程，阻塞等待模型输出不占平台线程。 */
    private static final ExecutorService STREAMS = Executors.newVirtualThreadPerTaskExecutor();

    private final ConversationAskService turns;
    private final CurrentUser currentUser;

    ConversationTurnController(ConversationAskService turns, CurrentUser currentUser) {
        this.turns = turns;
        this.currentUser = currentUser;
    }

    /** @param replacesTurnId 重新生成或修改问题时，指向要替换的最后一轮 */
    record TurnRequest(String question, Long replacesTurnId) {
    }

    /**
     * 提问（SSE）。事件依次是 {@code turn}（{"id"}，停止生成要用它）、若干 {@code status}（{"text": 处理阶段}）和
     * {@code delta}（{"text": 片段}）、一个 {@code done}（这一轮的最终结果，拒答或停止时客户端要用它替换已显示的文字）。
     * 出错时发 {@code error}（{"code", "detail"}）。员工断开连接后服务端仍把这一轮生成完并保存。
     */
    @PostMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter stream(@PathVariable long conversationId, @RequestBody TurnRequest request) {
        var pending = turns.start(currentUser.tenantId(), currentUser.userId(), conversationId, request.question(),
                request.replacesTurnId());
        var emitter = new SseEmitter(STREAM_TIMEOUT_MILLIS);
        var client = new Client(emitter);
        client.send("turn", Map.of("id", pending.turnId()));
        STREAMS.execute(() -> {
            try {
                var turn = turns.run(pending, new ConversationAskService.Events(
                        status -> client.send("status", Map.of("text", status)),
                        delta -> client.send("delta", Map.of("text", delta))));
                client.send("done", turn);
                client.complete();
            } catch (BusinessException e) {
                client.send("error", Map.of("code", e.code(), "detail", e.getMessage()));
                client.complete();
            } catch (RuntimeException e) {
                // 后台线程里的意外错误不经过全局异常处理器，在这里记日志并告诉前端，不让连接一直挂到超时。
                LOG.error("会话提问出错", e);
                client.send("error", Map.of("code", "INTERNAL_ERROR", "detail", "回答时出错，请稍后再试"));
                client.complete();
            }
        });
        return emitter;
    }

    /** 停止生成。只有这个操作会中止生成；这一轮已经结束时什么也不做。 */
    @PostMapping("/{turnId}/stop")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void stop(@PathVariable long conversationId, @PathVariable long turnId) {
        turns.stop(currentUser.tenantId(), currentUser.userId(), conversationId, turnId);
    }

    /** 发往浏览器的事件。员工断开后不再发送，但不中断生成（D-036）。 */
    private static final class Client {

        private final SseEmitter emitter;
        private final AtomicBoolean gone = new AtomicBoolean();

        Client(SseEmitter emitter) {
            this.emitter = emitter;
            emitter.onError(error -> gone.set(true));
            emitter.onTimeout(() -> gone.set(true));
        }

        void send(String event, Object data) {
            if (gone.get()) {
                return;
            }
            try {
                emitter.send(SseEmitter.event().name(event).data(data, MediaType.APPLICATION_JSON));
            } catch (IOException | IllegalStateException e) {
                gone.set(true);
            }
        }

        void complete() {
            if (!gone.get()) {
                emitter.complete();
            }
        }
    }
}
