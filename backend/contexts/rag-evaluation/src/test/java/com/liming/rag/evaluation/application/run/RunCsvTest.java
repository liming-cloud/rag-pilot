package com.liming.rag.evaluation.application.run;

import static org.assertj.core.api.Assertions.assertThat;

import com.liming.rag.evaluation.application.Change;
import com.liming.rag.evaluation.application.Expectation;
import com.liming.rag.evaluation.application.MatchMode;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RunCsvTest {

    @Test
    @DisplayName("带 BOM 和表头；含逗号、引号、换行的字段加引号，公式开头的加单引号")
    void writesEscapedRows() {
        var row = new ResultView(1, "临期, 怎么\"打折\"", "门店运营", Expectation.DOCUMENTS, MatchMode.ALL, false,
                Change.NEWLY_FAILED, true, 2, 1, 0, List.of("b.md"), "=1+1", List.of());

        var csv = RunCsv.write(List.of(row));

        assertThat(csv).startsWith("\uFEFF问题,知识库,期望,结果,和上一次比,拒答,召回,引用,没召回的文档,回答,引用的文档\r\n");
        assertThat(csv).contains("\"临期, 怎么\"\"打折\"\"\",门店运营,命中期望文档,未通过,新失败,是,1/2,0/2,b.md,'=1+1,\r\n");
    }
}
