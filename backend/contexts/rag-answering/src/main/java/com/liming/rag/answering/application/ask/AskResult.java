package com.liming.rag.answering.application.ask;

import com.liming.rag.answering.domain.AnswerRecord;
import com.liming.rag.answering.domain.Citation;
import com.liming.rag.answering.domain.DataCitation;
import com.liming.rag.answering.domain.RefusalReason;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * 一次提问的用例结果（#136：不是聚合，回答记录才是），问答接口原样返回给前端。领域里的“回答”是
 * {@link AnswerRecord.Reply}。拒答时 {@code text} 是给用户看的说明，{@code citations}、{@code dataCitations} 为空。
 * 记进回答记录的部分见 {@link #reply()}；改写情况和各阶段耗时只用于评测和排查（评测从流式接口的 done 事件读取）。
 *
 * @param id 回答记录的标识，用于点赞点踩；记录之前为 null
 * @param dataCitations 引用的数据出处（#182），序号与 {@code citations} 是同一套
 * @param topRelevance 重排后最高的相关度分数，没有检索结果时为 null；用于评测时校准拒答门槛
 * @param rewrite 低分时的改写情况，没有走到改写这一步时为 null
 * @param timings 各阶段耗时，用于评测和排查延迟
 */
public record AskResult(@Nullable Long id, String text, boolean refused, @Nullable RefusalReason refusalReason,
        List<Citation> citations, List<DataCitation> dataCitations, @Nullable Double topRelevance,
        @Nullable Rewrite rewrite, @Nullable Timings timings) {

    /** 只依据资料的回答。 */
    public static AskResult answered(String text, List<Citation> citations, double topRelevance) {
        return answered(text, citations, List.of(), topRelevance);
    }

    /** @param topRelevance 只依据数据回答（没有够分的资料）时为 null */
    public static AskResult answered(String text, List<Citation> citations, List<DataCitation> dataCitations,
            @Nullable Double topRelevance) {
        return new AskResult(null, text, false, null, List.copyOf(citations), List.copyOf(dataCitations), topRelevance,
                null, null);
    }

    /** 寒暄的回复（D-036）：没有检索、没有引用，也不含事实内容。 */
    public static AskResult smallTalk(String text) {
        return new AskResult(null, text, false, null, List.of(), List.of(), null, null, null);
    }

    /** 拒答：{@code text} 是给员工看的拒答话术，由系统设置提供（D-045）。 */
    public static AskResult refused(RefusalReason reason, @Nullable Double topRelevance, String text) {
        return new AskResult(null, text, true, reason, List.of(), List.of(), topRelevance, null, null);
    }

    public AskResult withId(long newId) {
        return new AskResult(newId, text, refused, refusalReason, citations, dataCitations, topRelevance, rewrite,
                timings);
    }

    public AskResult withTimings(Timings newTimings) {
        return new AskResult(id, text, refused, refusalReason, citations, dataCitations, topRelevance, rewrite,
                newTimings);
    }

    public AskResult withRewrite(Rewrite newRewrite) {
        return new AskResult(id, text, refused, refusalReason, citations, dataCitations, topRelevance, newRewrite,
                timings);
    }

    /** 改写后的问题；没有改写时为 null。 */
    public @Nullable String rewrittenQuestion() {
        return rewrite == null ? null : rewrite.question();
    }

    /** 换掉文字（如在回答后补一句“数据暂时查不到”），其余不变。 */
    public AskResult withText(String newText) {
        return new AskResult(id, newText, refused, refusalReason, citations, dataCitations, topRelevance, rewrite,
                timings);
    }

    /** 记进回答记录的部分：文字、是否拒答、引用、相关度和改写后的问题；耗时只用于评测，不记。 */
    public AnswerRecord.Reply reply() {
        return new AnswerRecord.Reply(text, refused, refusalReason, citations, dataCitations, topRelevance,
                rewrittenQuestion());
    }

    /** 回答记录里当时的回答，供会话展示历史；不带改写和耗时。 */
    public static AskResult of(AnswerRecord record) {
        var reply = record.reply();
        return new AskResult(record.id(), reply.text(), reply.refused(), reply.refusalReason(), reply.citations(),
                reply.dataCitations(), reply.topRelevance(), null, null);
    }
}
