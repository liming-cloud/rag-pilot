package com.liming.rag.answering.application.ask;

import com.liming.rag.answering.domain.DataCitation;
import com.liming.rag.answering.domain.RefusalReason;
import com.liming.rag.data.api.DataResult;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;

/**
 * 一次数据查询的结果，作为回答的另一类依据（规范第 7 节）：查到了就和资料一起给模型、可被引用；
 * 被拒或失败就如实告诉员工，不让模型编数据（规范第 6 节）。
 */
record DataEvidence(String tool, DataResult result) {

    /** 回答末尾补的一句，说明数据部分为什么没有。 */
    static final String NOTICE = "（数据部分：%s）";

    boolean found() {
        return result.status() == DataResult.Status.OK;
    }

    /** 没查到数据、资料里也没有答案时的拒答：话术就是数据查询给的说明（“无权查看……”“暂时查不到……”）。 */
    AskResult refusal(@Nullable Double topRelevance) {
        var reason = result.status() == DataResult.Status.DENIED ? RefusalReason.DATA_DENIED
                : RefusalReason.DATA_UNAVAILABLE;
        return AskResult.refused(reason, topRelevance, result.message());
    }

    /** 数据没查到时，资料的回答后面补一句说明；资料也答不上来就按数据的原因拒答。查到了原样返回。 */
    AskResult annotate(AskResult answer) {
        if (found()) {
            return answer;
        }
        if (answer.refused()) {
            return refusal(answer.topRelevance());
        }
        return answer.withText(answer.text() + "\n\n" + NOTICE.formatted(result.message()));
    }

    DataCitation citation(int index) {
        return new DataCitation(index, tool, result.system(), result.dataset(), conditions(result.query()),
                Objects.requireNonNull(result.asOf()), Objects.requireNonNull(result.queriedAt()), result.truncated());
    }

    /** 条件和结果行都写成 {@code 键=值，键=值}，给模型看，也给员工看引用。 */
    static String conditions(Map<String, ?> values) {
        return values.entrySet().stream().map(entry -> entry.getKey() + "=" + entry.getValue())
                .collect(Collectors.joining("，"));
    }
}
