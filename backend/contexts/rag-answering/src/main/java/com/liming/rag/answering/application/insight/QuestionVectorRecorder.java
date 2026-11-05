package com.liming.rag.answering.application.insight;

import com.liming.rag.ai.EmbeddingClient;
import com.liming.rag.ai.ModelCallException;
import com.liming.rag.answering.application.AnswerRecorded;
import java.util.Map;
import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 回答记下（事务提交）后在后台算问题向量，存进这条记录（#145 C6，修订 D-047）：知识缺口归类时直接用，不再每次现算。
 * 检索时已算过同一问题的向量，这里读缓存（D-053）。嵌入模型不可用时只记日志，回答不受影响；没算成的由 {@link KnowledgeGapService} 归类时补算。
 */
@Component
class QuestionVectorRecorder {

    private static final Logger log = LoggerFactory.getLogger(QuestionVectorRecorder.class);

    private final EmbeddingClient embeddings;
    private final GapRecords gapRecords;
    private final Executor background;

    /** @param background Spring 管理的虚拟线程执行器（{@code applicationTaskExecutor}），停机时等正在跑的任务做完 */
    QuestionVectorRecorder(EmbeddingClient embeddings, GapRecords gapRecords,
            @Qualifier("applicationTaskExecutor") Executor background) {
        this.embeddings = embeddings;
        this.gapRecords = gapRecords;
        this.background = background;
    }

    @TransactionalEventListener
    void on(AnswerRecorded event) {
        background.execute(() -> {
            try {
                var vector = embeddings.embedQuestion(event.question());
                gapRecords.saveVectors(event.tenantId(), Map.of(event.answerId(), vector));
            } catch (ModelCallException e) {
                log.warn("回答 {} 的问题向量没算成，知识缺口归类时再补：{}", event.answerId(), e.getMessage());
            }
        });
    }
}
