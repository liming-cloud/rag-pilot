package com.liming.rag.answering.application.insight;

import com.liming.rag.answering.application.ModelUnavailableException;
import java.util.List;

/** 问题向量化端口（D-054）：知识缺口按问题向量归类。 */
public interface QuestionEmbeddings {

    /**
     * @return 与输入同序的向量
     * @throws ModelUnavailableException 嵌入模型不可用
     */
    List<float[]> embed(List<String> questions);

    /**
     * 一个问题的向量；实现可以按问题缓存（检索时已算过同一问题）。
     *
     * @throws ModelUnavailableException 同 {@link #embed(List)}
     */
    default float[] embed(String question) {
        return embed(List.of(question)).getFirst();
    }
}
