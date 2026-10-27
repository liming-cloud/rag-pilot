package com.liming.rag.answering.web;

import cn.dev33.satoken.annotation.SaCheckRole;
import com.liming.rag.answering.application.AnswerLogService;
import com.liming.rag.answering.application.AnswerReview;
import com.liming.rag.identity.api.CurrentUser;
import com.liming.rag.identity.api.Roles;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 管理员查看回答记录：被点踩的、答不上来的，用来改进知识库和补充评测集。 */
@RestController
@RequestMapping("/api/answers")
@SaCheckRole(Roles.ADMIN)
class AnswerReviewController {

    private final AnswerLogService log;
    private final CurrentUser currentUser;

    AnswerReviewController(AnswerLogService log, CurrentUser currentUser) {
        this.log = log;
        this.currentUser = currentUser;
    }

    @GetMapping
    List<AnswerReview> list(@RequestParam(name = "filter", defaultValue = "disliked") String filter,
            @RequestParam(name = "limit", defaultValue = "50") int limit) {
        return log.review(currentUser.tenantId(), filter, limit);
    }
}
