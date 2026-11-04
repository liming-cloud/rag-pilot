package com.liming.rag.answering.infrastructure.persistence;

import java.time.LocalDate;

/** 某一天的使用量（#136）：MyBatis 按列名（下划线转驼峰）直接给字段赋值，只在 infrastructure 包内读取。 */
final class DailyUsageDO {

    LocalDate day;
    Integer questions;
    Integer refused;
    Integer disliked;
}
