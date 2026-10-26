package com.liming.rag.answering.web;

import com.liming.rag.answering.api.Answer;
import com.liming.rag.answering.application.AnswerService;
import com.liming.rag.common.TenantId;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 问答接口。试点阶段租户固定为配置值；接入 Sa-Token 后改为从登录会话取租户，并只允许问有权限的知识库。
 */
@RestController
@RequestMapping("/api/ask")
class AskController {

    private final AnswerService answers;
    private final TenantId pilotTenant;

    AskController(AnswerService answers, @Value("${rag.pilot.tenant-id:1}") long pilotTenantId) {
        this.answers = answers;
        this.pilotTenant = new TenantId(pilotTenantId);
    }

    record AskRequest(String question, List<Long> knowledgeBaseIds) {
    }

    @PostMapping
    Answer ask(@RequestBody AskRequest request) {
        return answers.ask(pilotTenant, request.knowledgeBaseIds(), request.question());
    }
}
