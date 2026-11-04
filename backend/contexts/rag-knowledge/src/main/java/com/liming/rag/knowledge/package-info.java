/**
 * 核心限界上下文：知识库、文档、分块、成员授权、混合检索、文件解析。
 *
 * <p>其他模块只能使用 {@code api} 包里的接口、record 和常量。domain 包放聚合和值对象；目前不发布领域事件，跨上下文的
 * 通知只有监听 identity 的 {@code RoleDeleted}（同一事务里同步收回授权）。MyBatis-Plus 的 DO、Wrapper、Page
 * 只允许出现在 infrastructure 包。
 */
// 允许依赖的模块（#136）：上下文只能依赖这些模块，以及它们 api 包公开的类型；方向见 AGENTS.md“上下文依赖”。
@ApplicationModule(allowedDependencies = {"shared", "ai", "identity :: api", "settings :: api"})
@NullMarked
package com.liming.rag.knowledge;

import org.jspecify.annotations.NullMarked;
import org.springframework.modulith.ApplicationModule;
