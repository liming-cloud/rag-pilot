package com.liming.rag.answering.infrastructure.persistence;

import org.jspecify.annotations.NullUnmarked;

/** 一段时间的使用合计（#136）：MyBatis 按列名（下划线转驼峰）直接给字段赋值，只在 infrastructure 包内读取。 */
@NullUnmarked
final class UsageTotalsDO {

    Integer questions;
    Integer askers;
    Integer refused;
    Integer rated;
    Integer liked;
    Integer disliked;
    Integer openReviews;
}
