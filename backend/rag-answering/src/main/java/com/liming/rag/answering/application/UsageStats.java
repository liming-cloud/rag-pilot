package com.liming.rag.answering.application;

import java.time.LocalDate;
import java.util.List;

/**
 * 一段时间内的使用统计，日期按北京时间。评价相关的数只算给出了回答的记录。
 *
 * @param from 第一天（含）
 * @param to 最后一天（含）
 * @param daily 每天一条，没有提问的日子也有，数都是 0
 */
public record UsageStats(LocalDate from, LocalDate to, Totals totals, List<Day> daily,
        List<ReasonCount> refusalReasons, List<QuestionCount> topRefusedQuestions,
        List<DocumentCount> topCitedDocuments) {

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
