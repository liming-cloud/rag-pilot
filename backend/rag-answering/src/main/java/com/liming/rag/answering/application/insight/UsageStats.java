package com.liming.rag.answering.application.insight;

import java.time.LocalDate;
import java.util.List;

/**
 * 一段时间内的使用统计，日期按部署配置的统计时区划分（D-045）。评价相关的数只算给出了回答的记录。
 *
 * @param from 第一天（含）
 * @param to 最后一天（含）
 * @param zone 统计时区，如 Asia/Shanghai
 * @param previous 按周查看时上一周同样天数的汇总，用来对比；按最近 N 天查看时为 null（#125）
 * @param daily 每天一条，没有提问的日子也有，数都是 0
 * @param topGaps 待处理次数最多的知识缺口（按意思归类，D-047）：代表问题和待处理次数
 */
public record UsageStats(LocalDate from, LocalDate to, String zone, Totals totals, Totals previous, List<Day> daily,
        List<ReasonCount> refusalReasons, List<QuestionCount> topGaps, List<DocumentCount> topCitedDocuments) {

    public UsageStats withComparison(Totals previousTotals, List<QuestionCount> gaps) {
        return new UsageStats(from, to, zone, totals, previousTotals, daily, refusalReasons, List.copyOf(gaps),
                topCitedDocuments);
    }

    /**
     * @param rated 给出了回答、且被点了赞或踩的记录数
     * @param openReviews 差评和答不上来、还没标记已处理的记录数
     */
    public record Totals(int questions, int askers, int refused, int rated, int liked, int disliked, int openReviews) {
    }

    public record Day(LocalDate date, int questions, int refused, int disliked) {
    }

    public record ReasonCount(String reason, int count) {
    }

    public record QuestionCount(String question, int count) {
    }

    /** @param count 引用过这份文档的回答数 */
    public record DocumentCount(String documentName, int count) {
    }
}
