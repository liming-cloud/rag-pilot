package com.liming.rag.answering.web;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.liming.rag.answering.api.Answer;
import com.liming.rag.answering.application.AnswerLogService;
import com.liming.rag.answering.application.AnswerReview;
import com.liming.rag.answering.application.AnswerService;
import com.liming.rag.answering.application.UsageStats;
import com.liming.rag.identity.api.CurrentUser;
import com.liming.rag.identity.api.Permissions;
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

/** 查看、处理回答记录（被点踩的、答不上来的，用来改进知识库和补充评测集）和使用统计，每个操作对应一个权限点。 */
@RestController
@RequestMapping("/api/answers")
@SaCheckLogin
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

    /** 分页响应（D-039）：{@code {items, total, page, size}}。 */
    record PageView<T>(List<T> items, long total, int page, int size) {
    }

    /** 按类型、处理状态、最近几天、问题关键词筛选，分页（D-039）。 */
    @SaCheckPermission(Permissions.ANSWER_VIEW)
    @GetMapping
    PageView<AnswerReview> list(@RequestParam(name = "filter", defaultValue = "disliked") String filter,
            @RequestParam(name = "status", defaultValue = "open") String status,
            @RequestParam(name = "days", required = false) Integer days,
            @RequestParam(name = "keyword", required = false) String keyword,
            @RequestParam(name = "page", defaultValue = "1") int page,
            @RequestParam(name = "size", defaultValue = "20") int size) {
        var found = log.review(currentUser.tenantId(), filter, status, days, keyword, page, size);
        return new PageView<>(found.items(), found.total(), found.page(), found.size());
    }

    /** 最近几天（含今天）的使用统计，供试点汇报。 */
    @SaCheckPermission(Permissions.STATS_VIEW)
    @GetMapping("/stats")
    UsageStats stats(@RequestParam(name = "days", defaultValue = "30") int days) {
        return log.usage(currentUser.tenantId(), days);
    }

    /** 标记已处理（附处理说明）或重新打开。 */
    @SaCheckPermission(Permissions.ANSWER_REVIEW)
    @PatchMapping("/{answerId}/review")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void review(@PathVariable long answerId, @RequestBody ReviewRequest request) {
        log.markReview(currentUser.tenantId(), currentUser.userId(), answerId, request.status(), request.note());
    }

    /**
     * 用当前知识库重新回答这条记录里的问题，确认补的文档是否解决了问题。按管理员自己的权限检索，
     * 结果不写入回答记录。
     */
    @SaCheckPermission(Permissions.ANSWER_RECHECK)
    @PostMapping("/{answerId}/recheck")
    Answer recheck(@PathVariable long answerId) {
        var question = log.questionOf(currentUser.tenantId(), answerId);
        return answers.ask(currentUser.tenantId(), currentUser.userId(), null, question);
    }
}
