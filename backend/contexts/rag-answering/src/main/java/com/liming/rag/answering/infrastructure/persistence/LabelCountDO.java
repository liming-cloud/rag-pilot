package com.liming.rag.answering.infrastructure.persistence;

/** 排行的一项（#136）：MyBatis 按列名（下划线转驼峰）直接给字段赋值，只在 infrastructure 包内读取。 */
final class LabelCountDO {

    String label;
    Integer count;
}
