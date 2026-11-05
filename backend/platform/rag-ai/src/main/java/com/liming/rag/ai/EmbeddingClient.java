package com.liming.rag.ai;

import java.util.List;

/**
 * 文本向量化端口。知识模块入库和检索都只依赖这个接口，不直接接触厂商 SDK。
 */
public interface EmbeddingClient {

    /** 与数据库 chunk.embedding 列的维度一致；换模型时两边一起改。 */
    int DIMENSIONS = 1024;

    /**
     * @return 与输入同序、长度均为 {@link #DIMENSIONS} 的向量
     * @throws ModelCallException 当模型服务不可用或返回的向量维度不对时
     */
    List<float[]> embed(List<String> texts);

    /**
     * 一个问题的向量。同一个问题检索时、记回答时各要一次，生产实现按模型名和问题缓存（D-053），只调一次模型。
     *
     * @throws ModelCallException 同 {@link #embed(List)}
     */
    default float[] embedQuestion(String question) {
        return embed(List.of(question)).getFirst();
    }
}
