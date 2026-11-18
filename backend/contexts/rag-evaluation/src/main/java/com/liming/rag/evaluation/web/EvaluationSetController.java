package com.liming.rag.evaluation.web;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.liming.rag.evaluation.api.EvaluationPermissions;
import com.liming.rag.evaluation.application.suite.DocumentOption;
import com.liming.rag.evaluation.application.suite.ImportOutcome;
import com.liming.rag.evaluation.application.suite.ImportRow;
import com.liming.rag.evaluation.application.suite.KnowledgeBaseOption;
import com.liming.rag.evaluation.application.suite.QuestionImport;
import com.liming.rag.evaluation.application.suite.QuestionInput;
import com.liming.rag.evaluation.application.suite.QuestionView;
import com.liming.rag.evaluation.application.suite.SetView;
import com.liming.rag.evaluation.application.suite.SuiteService;
import com.liming.rag.identity.api.CurrentUser;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 知识质量的题集和标准题（#224）。查看人、操作人无权访问的库里的题当作不存在。 */
@RestController
@RequestMapping("/api/evaluation")
@SaCheckLogin
class EvaluationSetController {

    record SetRequest(@Nullable String name, @Nullable String description) {
    }

    record QuestionRequest(@Nullable String question, long knowledgeBaseId, @Nullable String expectation,
            @Nullable String match, @Nullable List<Long> expectedDocumentIds) {

        QuestionInput input() {
            return new QuestionInput(question, knowledgeBaseId, expectation, match, expectedDocumentIds);
        }
    }

    record ImportRequest(@Nullable List<ImportRow> rows) {
    }

    private final SuiteService suites;
    private final QuestionImport imports;
    private final CurrentUser currentUser;

    EvaluationSetController(SuiteService suites, QuestionImport imports, CurrentUser currentUser) {
        this.suites = suites;
        this.imports = imports;
        this.currentUser = currentUser;
    }

    @SaCheckPermission(EvaluationPermissions.EVAL_VIEW)
    @GetMapping("/sets")
    List<SetView> sets() {
        return suites.sets(currentUser.tenantId(), currentUser.userId());
    }

    @SaCheckPermission(EvaluationPermissions.EVAL_VIEW)
    @GetMapping("/sets/{setId}")
    SetView set(@PathVariable long setId) {
        return suites.set(currentUser.tenantId(), currentUser.userId(), setId);
    }

    @SaCheckPermission(EvaluationPermissions.EVAL_MANAGE)
    @PostMapping("/sets")
    @ResponseStatus(HttpStatus.CREATED)
    SetView createSet(@RequestBody SetRequest request) {
        return suites.createSet(currentUser.tenantId(), currentUser.userId(), request.name(), request.description());
    }

    @SaCheckPermission(EvaluationPermissions.EVAL_MANAGE)
    @PutMapping("/sets/{setId}")
    SetView updateSet(@PathVariable long setId, @RequestBody SetRequest request) {
        return suites.updateSet(currentUser.tenantId(), currentUser.userId(), setId, request.name(),
                request.description());
    }

    @SaCheckPermission(EvaluationPermissions.EVAL_MANAGE)
    @DeleteMapping("/sets/{setId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void deleteSet(@PathVariable long setId) {
        suites.deleteSet(currentUser.tenantId(), setId);
    }

    @SaCheckPermission(EvaluationPermissions.EVAL_VIEW)
    @GetMapping("/sets/{setId}/questions")
    List<QuestionView> questions(@PathVariable long setId) {
        return suites.questions(currentUser.tenantId(), currentUser.userId(), setId);
    }

    @SaCheckPermission(EvaluationPermissions.EVAL_MANAGE)
    @PostMapping("/sets/{setId}/questions")
    @ResponseStatus(HttpStatus.CREATED)
    QuestionView addQuestion(@PathVariable long setId, @RequestBody QuestionRequest request) {
        return suites.addQuestion(currentUser.tenantId(), currentUser.userId(), setId, request.input());
    }

    @SaCheckPermission(EvaluationPermissions.EVAL_MANAGE)
    @PutMapping("/sets/{setId}/questions/{questionId}")
    QuestionView updateQuestion(@PathVariable long setId, @PathVariable long questionId,
            @RequestBody QuestionRequest request) {
        return suites.updateQuestion(currentUser.tenantId(), currentUser.userId(), setId, questionId, request.input());
    }

    @SaCheckPermission(EvaluationPermissions.EVAL_MANAGE)
    @DeleteMapping("/sets/{setId}/questions/{questionId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void deleteQuestion(@PathVariable long setId, @PathVariable long questionId) {
        suites.deleteQuestion(currentUser.tenantId(), currentUser.userId(), setId, questionId);
    }

    /** CSV 由前端解析成行；知识库、期望文档按名称在操作人能访问的库里匹配，匹配不上的行跳过。 */
    @SaCheckPermission(EvaluationPermissions.EVAL_MANAGE)
    @PostMapping("/sets/{setId}/questions/import")
    ImportOutcome importQuestions(@PathVariable long setId, @RequestBody ImportRequest request) {
        return imports.importRows(currentUser.tenantId(), currentUser.userId(), setId,
                request.rows() == null ? List.of() : request.rows());
    }

    /** 试问和标准题可选的知识库：当前账号能访问的库。 */
    @SaCheckPermission(EvaluationPermissions.EVAL_VIEW)
    @GetMapping("/knowledge-bases")
    List<KnowledgeBaseOption> knowledgeBases() {
        return suites.knowledgeBases(currentUser.tenantId(), currentUser.userId());
    }

    /** 选期望文档：能访问的库里已入库的文档，按文件名搜。 */
    @SaCheckPermission(EvaluationPermissions.EVAL_MANAGE)
    @GetMapping("/knowledge-bases/{knowledgeBaseId}/documents")
    List<DocumentOption> documents(@PathVariable long knowledgeBaseId,
            @RequestParam(name = "keyword", required = false) String keyword) {
        return suites.documents(currentUser.tenantId(), currentUser.userId(), knowledgeBaseId, keyword);
    }
}
