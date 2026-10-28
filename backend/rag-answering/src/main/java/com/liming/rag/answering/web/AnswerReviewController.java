package com.liming.rag.answering.web;

import cn.dev33.satoken.annotation.SaCheckRole;
import com.liming.rag.answering.api.Answer;
import com.liming.rag.answering.application.AnswerLogService;
import com.liming.rag.answering.application.AnswerReview;
import com.liming.rag.answering.application.AnswerService;
import com.liming.rag.identity.api.CurrentUser;
import com.liming.rag.identity.api.Roles;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 管理员查看、处理回答记录：被点踩的、答不上来的，用来改进知识库和补充评测集。 */
@RestController
@RequestMapping("/api/answers")
@SaCheckRole(Roles.ADMIN)
class AnswerReviewController {

    record ReviewRequest(String status, String note) {
    }

    private final AnswerLogService log;
    private final AnswerService answers;
    private final CurrentUser currentUser;

    AnswerReviewController(AnswerLogService log, AnswerService answers, CurrentUser currentUser) {
        this.log = log;
        this.answers = answers;
        this.currentUser = currentUser;
    }

    @GetMapping
    List<AnswerReview> list(@RequestParam(name = "filter", defaultValue = "disliked") String filter,
            @RequestParam(name = "includeResolved", defaultValue = "false") boolean includeResolved,
            @RequestParam(name = "limit", defaultValue = "50") int limit) {
        return log.review(currentUser.tenantId(), filter, includeResolved, limit);
    }

    /** 标记已处理（附处理说明）或重新打开。 */
    @PatchMapping("/{answerId}/review")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void review(@PathVariable long answerId, @RequestBody ReviewRequest request) {
        log.markReview(currentUser.tenantId(), currentUser.userId(), answerId, request.status(), request.note());
    }

    /**
     * 用当前知识库重新回答这条记录里的问题，确认补的文档是否解决了问题。按管理员自己的权限检索，
     * 结果不写入回答记录。
     */
    @PostMapping("/{answerId}/recheck")
    Answer recheck(@PathVariable long answerId) {
        var question = log.questionOf(currentUser.tenantId(), answerId);
        return answers.ask(currentUser.tenantId(), currentUser.userId(), null, question);
    }
}
