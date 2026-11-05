package com.liming.rag.knowledge.application;

import com.liming.rag.knowledge.api.EmbeddingUnavailableException;
import java.util.List;

/** 向量化端口（D-054）：入库时给分块算向量，检索时给问题算向量。 */
public interface TextEmbedding {

    /**
     * @return 与输入同序的向量，维度与 chunk.embedding 列一致
     * @throws EmbeddingUnavailableException 嵌入模型不可用
     */
    List<float[]> embed(List<String> texts);

    /**
     * 一个问题的向量；实现可以按问题缓存。
     *
     * @throws EmbeddingUnavailableException 同 {@link #embed(List)}
     */
    default float[] embedQuestion(String question) {
        return embed(List.of(question)).getFirst();
    }
}
