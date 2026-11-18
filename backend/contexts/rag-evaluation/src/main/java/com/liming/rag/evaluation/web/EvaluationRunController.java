package com.liming.rag.evaluation.web;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.liming.rag.evaluation.api.EvaluationPermissions;
import com.liming.rag.evaluation.application.RunSummary;
import com.liming.rag.evaluation.application.run.RunDetail;
import com.liming.rag.evaluation.application.run.RunEstimate;
import com.liming.rag.evaluation.application.run.RunService;
import com.liming.rag.evaluation.application.trial.TrialResult;
import com.liming.rag.evaluation.application.trial.TrialService;
import com.liming.rag.identity.api.CurrentUser;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 知识质量的试问和运行（#224）：按当前账号的权限回答，不写回答记录，用量记为“评测”。 */
@RestController
@RequestMapping("/api/evaluation")
@SaCheckLogin
class EvaluationRunController {

    record TrialRequest(long knowledgeBaseId, @Nullable String question) {
    }

    private final TrialService trials;
    private final RunService runs;
    private final CurrentUser currentUser;

    EvaluationRunController(TrialService trials, RunService runs, CurrentUser currentUser) {
        this.trials = trials;
        this.runs = runs;
        this.currentUser = currentUser;
    }

    @SaCheckPermission(EvaluationPermissions.EVAL_RUN)
    @PostMapping("/trial")
    TrialResult trial(@RequestBody TrialRequest request) {
        return trials.ask(currentUser.tenantId(), currentUser.userId(), request.knowledgeBaseId(), request.question());
    }

    @SaCheckPermission(EvaluationPermissions.EVAL_RUN)
    @GetMapping("/sets/{setId}/estimate")
    RunEstimate estimate(@PathVariable long setId) {
        return runs.estimate(currentUser.tenantId(), currentUser.userId(), setId);
    }

    @SaCheckPermission(EvaluationPermissions.EVAL_RUN)
    @PostMapping("/sets/{setId}/runs")
    @ResponseStatus(HttpStatus.CREATED)
    RunSummary start(@PathVariable long setId) {
        return runs.start(currentUser.tenantId(), currentUser.userId(), setId);
    }

    @SaCheckPermission(EvaluationPermissions.EVAL_VIEW)
    @GetMapping("/sets/{setId}/runs")
    List<RunSummary> runs(@PathVariable long setId) {
        return runs.runs(currentUser.tenantId(), currentUser.userId(), setId);
    }

    @SaCheckPermission(EvaluationPermissions.EVAL_VIEW)
    @GetMapping("/runs/{runId}")
    RunDetail run(@PathVariable long runId) {
        return runs.run(currentUser.tenantId(), currentUser.userId(), runId);
    }

    /** 导出运行结果的 CSV（UTF-8 带 BOM），只含查看人能看到的题。 */
    @SaCheckPermission(EvaluationPermissions.EVAL_VIEW)
    @GetMapping("/runs/{runId}/export")
    ResponseEntity<byte[]> export(@PathVariable long runId) {
        var export = runs.export(currentUser.tenantId(), currentUser.userId(), runId);
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(export.filename(), StandardCharsets.UTF_8).build().toString())
                .body(export.csv().getBytes(StandardCharsets.UTF_8));
    }
}
