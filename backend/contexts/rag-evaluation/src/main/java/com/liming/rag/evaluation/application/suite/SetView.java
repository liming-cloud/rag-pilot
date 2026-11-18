package com.liming.rag.evaluation.application.suite;

import com.liming.rag.evaluation.application.RunSummary;
import java.time.OffsetDateTime;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * 题集列表的一行，题数只数查看人能看到的题。
 *
 * @param staleCount 其中有期望文档已删除的题数
 * @param knowledgeBaseNames 这些题涉及的知识库
 * @param lastRun 最近一次运行（按查看人汇总），还没运行过时为 null
 */
public record SetView(long id, String name, String description, int questionCount, int staleCount,
        List<String> knowledgeBaseNames, @Nullable RunSummary lastRun, OffsetDateTime updatedAt) {
}
