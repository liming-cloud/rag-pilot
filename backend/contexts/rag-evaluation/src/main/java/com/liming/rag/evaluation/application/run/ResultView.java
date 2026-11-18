package com.liming.rag.evaluation.application.run;

import com.liming.rag.evaluation.application.Change;
import com.liming.rag.evaluation.application.Expectation;
import com.liming.rag.evaluation.application.MatchMode;
import java.util.List;

/**
 * 运行结果里跑完的一道题。
 *
 * @param expectedCount 期望文档数，应当拒答的题为 0
 * @param recalledCount 召回里命中的期望文档数
 * @param citedCount 引用里命中的期望文档数
 * @param missingDocuments 没召回的期望文档名，帮助判断是资料问题还是检索问题
 */
public record ResultView(long questionId, String question, String knowledgeBaseName, Expectation expectation,
        MatchMode match, boolean passed, Change change, boolean refused, int expectedCount, int recalledCount,
        int citedCount, List<String> missingDocuments, String answerText, List<String> citedDocuments) {
}
