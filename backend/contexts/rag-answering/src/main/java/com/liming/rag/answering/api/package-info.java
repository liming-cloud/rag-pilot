/**
 * 本模块对外公开的合同：权限点常量（{@link AnsweringPermissions}），以及知识质量用的只读回答入口（{@link AnswerProbe}，#224）。回答结果在 application（{@code AskResult}），
 * 回答记录在 domain，其他上下文不直接用它们；真有上下文要用时再按需定义合同（#136）。
 */
@NamedInterface("api")
@NullMarked
package com.liming.rag.answering.api;

import org.jspecify.annotations.NullMarked;
import org.springframework.modulith.NamedInterface;
