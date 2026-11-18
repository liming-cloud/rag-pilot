/**
 * 限界上下文：评测集、指标计算和报告，以及管理端的“知识质量”（#224、D-069、D-070）。
 *
 * <p>离线评测：rag-app 的评测运行（测试代码里的 {@code EvaluationRun}）经 HTTP 接口提问，结果写成 Markdown 报告
 * （CI 的运行摘要），评判规则在 {@code api}。知识质量：题集、标准题和每次运行的结果存库；试问和运行经 answering 的
 * {@code AnswerProbe} 按运行人的权限回答，不写回答记录；查看人、运行人无权访问的库里的题当作不存在。
 */
// 允许依赖的模块（#136、D-070、D-072）：上下文只能依赖这些模块，以及它们 api 包公开的类型；方向见 规则卡 A-10。
@ApplicationModule(allowedDependencies = {"shared", "ai", "ai :: common", "identity :: api", "knowledge :: api",
    "answering :: api", "settings :: api"})
@NullMarked
package com.liming.rag.evaluation;

import org.jspecify.annotations.NullMarked;
import org.springframework.modulith.ApplicationModule;
