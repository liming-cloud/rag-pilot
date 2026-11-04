package com.liming.rag.answering.web;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.liming.rag.answering.api.AnsweringPermissions;
import com.liming.rag.answering.application.ask.AskResult;
import com.liming.rag.answering.application.review.AnswerLogService;
import com.liming.rag.answering.application.review.AnswerReview;
import com.liming.rag.answering.application.ask.AnswerService;
import com.liming.rag.answering.application.insight.KnowledgeGapService;
import com.liming.rag.answering.application.insight.UsageReportService;
import com.liming.rag.answering.application.insight.UsageStats;
import com.liming.rag.identity.api.CurrentUser;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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

    record ResolveGapRequest(List<Long> recordIds, String note) {
    }

    record ResolveGapResponse(int resolved) {
    }

    private final AnswerLogService log;
    private final AnswerService answers;
    private final UsageReportService usage;
    private final KnowledgeGapService gaps;
    private final CurrentUser currentUser;

    AnswerReviewController(AnswerLogService log, AnswerService answers, UsageReportService usage,
            KnowledgeGapService gaps, CurrentUser currentUser) {
        this.log = log;
        this.answers = answers;
        this.usage = usage;
        this.gaps = gaps;
        this.currentUser = currentUser;
    }

    /** 分页响应（D-039）：{@code {items, total, page, size}}。 */
    record PageView<T>(List<T> items, long total, int page, int size) {
    }

    /** 按类型、处理状态、最近几天、问题关键词筛选，分页（D-039）。 */
    @SaCheckPermission(AnsweringPermissions.ANSWER_VIEW)
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

    /** 最近几天（含今天）或某个自然周的使用统计，按周时与上一周对比（#125）。 */
    @SaCheckPermission(AnsweringPermissions.STATS_VIEW)
    @GetMapping("/stats")
    UsageStats stats(@RequestParam(name = "days", required = false) Integer days,
            @RequestParam(name = "week", required = false) String week) {
        return usage.usage(currentUser.tenantId(), days, week);
    }

    /** 导出这段时间每条问答的 CSV（UTF-8 带 BOM）。 */
    @SaCheckPermission(AnsweringPermissions.STATS_EXPORT)
    @GetMapping("/stats/export")
    ResponseEntity<byte[]> export(@RequestParam(name = "days", required = false) Integer days,
            @RequestParam(name = "week", required = false) String week) {
        var export = usage.export(currentUser.tenantId(), days, week);
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(export.filename(), StandardCharsets.UTF_8).build().toString())
                .body(export.csv().getBytes(StandardCharsets.UTF_8));
    }

    /** 知识缺口（D-047）：按意思归类的拒答和差评，status 为 open（有待处理的类）或 all。 */
    @SaCheckPermission(AnsweringPermissions.ANSWER_VIEW)
    @GetMapping("/gaps")
    KnowledgeGapService.Report gaps(@RequestParam(name = "days", defaultValue = "30") int days,
            @RequestParam(name = "status", defaultValue = "open") String status) {
        return gaps.report(currentUser.tenantId(), days, status);
    }

    /** 整类标记已处理：这些记录里还没处理的都标记已处理，附同一条说明。 */
    @SaCheckPermission(AnsweringPermissions.ANSWER_REVIEW)
    @PostMapping("/gaps/resolve")
    ResolveGapResponse resolveGap(@RequestBody ResolveGapRequest request) {
        return new ResolveGapResponse(gaps.resolve(currentUser.tenantId(), currentUser.userId(), request.recordIds(),
                request.note()));
    }

    /** 标记已处理（附处理说明）或重新打开。 */
    @SaCheckPermission(AnsweringPermissions.ANSWER_REVIEW)
    @PatchMapping("/{answerId}/review")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void review(@PathVariable long answerId, @RequestBody ReviewRequest request) {
        log.markReview(currentUser.tenantId(), currentUser.userId(), answerId, request.status(), request.note());
    }

    /**
     * 用当前知识库重新回答这条记录里的问题，确认补的文档是否解决了问题。按管理员自己的权限检索，
     * 结果不写入回答记录。
     */
    @SaCheckPermission(AnsweringPermissions.ANSWER_RECHECK)
    @PostMapping("/{answerId}/recheck")
    AskResult recheck(@PathVariable long answerId) {
        var question = log.questionOf(currentUser.tenantId(), answerId);
        return answers.ask(currentUser.tenantId(), currentUser.userId(), null, question);
    }
}
