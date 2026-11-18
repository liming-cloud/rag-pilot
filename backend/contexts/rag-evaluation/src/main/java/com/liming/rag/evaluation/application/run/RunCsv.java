package com.liming.rag.evaluation.application.run;

import com.liming.rag.evaluation.application.Change;
import com.liming.rag.evaluation.application.Expectation;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** 运行结果的 CSV：UTF-8 带 BOM，Excel 直接打开中文不乱码；字段按 RFC 4180 转义。 */
final class RunCsv {

    private static final String HEADER = "问题,知识库,期望,结果,和上一次比,拒答,召回,引用,没召回的文档,回答,引用的文档";
    private static final Map<Change, String> CHANGES = Map.of(Change.NEWLY_FAILED, "新失败", Change.NEWLY_PASSED,
            "新通过", Change.SAME, "不变", Change.NEW, "新加的题");

    private RunCsv() {
    }

    static String write(List<ResultView> results) {
        var lines = results.stream().map(RunCsv::line);
        return "\uFEFF" + Stream.concat(Stream.of(HEADER), lines).collect(Collectors.joining("\r\n")) + "\r\n";
    }

    private static String line(ResultView result) {
        var refusal = result.expectation() == Expectation.REFUSAL;
        return Stream.of(result.question(), result.knowledgeBaseName(), refusal ? "应当拒答" : "命中期望文档",
                        result.passed() ? "通过" : "未通过", CHANGES.get(result.change()), result.refused() ? "是" : "否",
                        refusal ? "" : result.recalledCount() + "/" + result.expectedCount(),
                        refusal ? "" : result.citedCount() + "/" + result.expectedCount(),
                        String.join("|", result.missingDocuments()), result.answerText(),
                        String.join("|", result.citedDocuments()))
                .map(RunCsv::escape).collect(Collectors.joining(","));
    }

    /** 含逗号、引号、换行的字段用双引号包起来；以 = + - @ 开头的前面加单引号，防止 Excel 当公式执行。 */
    static String escape(String value) {
        var safe = !value.isEmpty() && "=+-@".indexOf(value.charAt(0)) >= 0 ? "'" + value : value;
        if (safe.contains(",") || safe.contains("\"") || safe.contains("\n") || safe.contains("\r")) {
            return "\"" + safe.replace("\"", "\"\"") + "\"";
        }
        return safe;
    }
}
