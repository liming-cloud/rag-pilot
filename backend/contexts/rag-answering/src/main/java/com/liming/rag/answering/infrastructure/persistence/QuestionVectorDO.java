package com.liming.rag.answering.infrastructure.persistence;

/** 要写回的一条问题向量（#145 C6），只作 Mapper 参数。 */
final class QuestionVectorDO {

    Long id;
    Float[] vector;

    QuestionVectorDO(long id, float[] vector) {
        this.id = id;
        this.vector = new Float[vector.length];
        for (int i = 0; i < vector.length; i++) {
            this.vector[i] = vector[i];
        }
    }
}
