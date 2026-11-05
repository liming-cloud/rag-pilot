package com.liming.rag.answering.infrastructure.persistence;

import org.jspecify.annotations.NullUnmarked;

/** 同一员工短时间内先后两次提问的向量：MyBatis 按列名（下划线转驼峰）给字段赋值，只在 infrastructure 包内读取。 */
@NullUnmarked
final class RepeatPairDO {

    Long recordId;
    Float[] previousVector;
    Float[] currentVector;
}
