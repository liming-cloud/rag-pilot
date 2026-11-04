/**
 * 限界上下文：评测集、指标计算和报告。
 *
 * <p>目前只被 rag-app 的评测运行（测试代码里的 {@code EvaluationRun}）使用，评测运行经 HTTP 接口提问；评测结果写成
 * Markdown 报告（CI 的运行摘要）。之后要把结果写入 eval_runs 时，再按上下文补齐分层（#136 Q9）。
 */
// 允许依赖的模块（#136）：上下文只能依赖这些模块，以及它们 api 包公开的类型；方向见 AGENTS.md“上下文依赖”。
@ApplicationModule(allowedDependencies = {"shared", "ai"})
package com.liming.rag.evaluation;

import org.springframework.modulith.ApplicationModule;
