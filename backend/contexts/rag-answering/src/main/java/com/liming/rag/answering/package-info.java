/**
 * 核心限界上下文：会话、回答、引用校验、拒答、反馈、知识缺口与运营统计。
 *
 * <p>其他模块只能使用 {@code api} 包里的常量（权限点）。domain 包放聚合和值对象；目前不发布领域事件。
 * MyBatis-Plus 的 DO、Wrapper、Page 只允许出现在 infrastructure 包。
 */
// 允许依赖的模块（#136）：上下文只能依赖这些模块，以及它们 api 包公开的类型；方向见 AGENTS.md“上下文依赖”。
@ApplicationModule(allowedDependencies = {"shared", "persistence", "ai", "identity :: api", "knowledge :: api", "settings :: api", "data :: api"})
@NullMarked
package com.liming.rag.answering;

import org.jspecify.annotations.NullMarked;
import org.springframework.modulith.ApplicationModule;
