package com.liming.rag.answering.web;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.liming.rag.answering.application.AskResult;
import com.liming.rag.answering.application.AnswerLogService;
import com.liming.rag.answering.application.AnswerService;
import com.liming.rag.identity.api.CurrentUser;
import com.liming.rag.identity.api.IdentityPermissions;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.AsyncTaskExecutor;
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
@SaCheckPermission(IdentityPermissions.CHAT_USE)
class AskController {

    private static final Logger LOG = LoggerFactory.getLogger(AskController.class);

    private final AnswerService answers;
    private final AnswerLogService log;
    private final CurrentUser currentUser;
    /** 每次流式问答一个虚拟线程，阻塞等待模型输出不占平台线程；停机时等它答完（#135）。 */
    private final AsyncTaskExecutor streams;

    AskController(AnswerService answers, AnswerLogService log, CurrentUser currentUser,
            @Qualifier("applicationTaskExecutor") AsyncTaskExecutor streams) {
        this.answers = answers;
        this.log = log;
        this.currentUser = currentUser;
        this.streams = streams;
    }

    record AskRequest(String question, List<Long> knowledgeBaseIds) {
    }

    @PostMapping
    AskResult ask(@RequestBody AskRequest request) {
        var tenantId = currentUser.tenantId();
        var userId = currentUser.userId();
        var answer = answers.ask(tenantId, userId, request.knowledgeBaseIds(), request.question());
        return log.record(tenantId, userId, request.question(), answer, request.knowledgeBaseIds());
    }

    /**
     * 流式问答（SSE）。事件依次是若干 {@code delta}（{"text": 片段}）和一个 {@code done}（完整的 AskResult）；
     * {@code done} 是最终结果，拒答时客户端要用它替换已显示的文字。出错时发 {@code error}（{"code", "detail"}，code 与 HTTP 接口的错误码相同）。
     * 处理中的状态（如低分时“换个说法再找找…”）发 {@code status}（{"text": 提示}）。
     */
    @PostMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter stream(@RequestBody AskRequest request) {
        // 登录信息只在请求线程里能取到，先取出来再交给后台线程。
        var tenantId = currentUser.tenantId();
        var userId = currentUser.userId();
        var channel = new SseChannel(SseChannel.OnDisconnect.STOP);
        streams.execute(() -> channel.run(events -> {
            var answer = answers.ask(tenantId, userId, request.knowledgeBaseIds(), request.question(),
                    delta -> events.send("delta", Map.of("text", delta)),
                    status -> events.send("status", Map.of("text", status)));
            events.send("done", log.record(tenantId, userId, request.question(), answer, request.knowledgeBaseIds()));
        }, LOG, "流式问答出错"));
        return channel.emitter();
    }
}
