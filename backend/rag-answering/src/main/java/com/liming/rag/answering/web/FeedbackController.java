package com.liming.rag.answering.web;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.liming.rag.answering.application.AnswerLogService;
import com.liming.rag.identity.api.CurrentUser;
import com.liming.rag.identity.api.Permissions;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 对回答点赞点踩。只有提问人本人能评价，别人的回答一律返回 404。 */
@RestController
@RequestMapping("/api/answers/{answerId}/feedback")
@SaCheckLogin
@SaCheckPermission(Permissions.CHAT_USE)
class FeedbackController {

    record FeedbackRequest(String rating, String comment) {
    }

    private final AnswerLogService log;
    private final CurrentUser currentUser;

    FeedbackController(AnswerLogService log, CurrentUser currentUser) {
        this.log = log;
        this.currentUser = currentUser;
    }

    @PutMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void rate(@PathVariable long answerId, @RequestBody FeedbackRequest request) {
        log.rate(currentUser.tenantId(), currentUser.userId(), answerId, request.rating(), request.comment());
    }

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void clear(@PathVariable long answerId) {
        log.clearRating(currentUser.tenantId(), currentUser.userId(), answerId);
    }
}
