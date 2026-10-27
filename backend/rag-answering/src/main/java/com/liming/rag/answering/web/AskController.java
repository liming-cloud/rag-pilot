package com.liming.rag.answering.web;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.liming.rag.answering.api.Answer;
import com.liming.rag.answering.application.AnswerService;
import com.liming.rag.identity.api.CurrentUser;
import java.util.List;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 问答接口。要登录，租户从登录会话取；只允许问有权限的知识库在下一步加入。
 */
@RestController
@RequestMapping("/api/ask")
@SaCheckLogin
class AskController {

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
        return answers.ask(currentUser.tenantId(), request.knowledgeBaseIds(), request.question());
    }
}
