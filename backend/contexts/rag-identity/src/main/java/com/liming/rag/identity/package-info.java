/**
 * 限界上下文：账号、登录、角色与权限点。
 *
 * <p>非核心领域，按普通 Service 加 MyBatis-Plus Mapper 实现，不建聚合。接口的登录与权限点校验统一用 Sa-Token 注解；
 * 会话和密码摘要的实现在 infrastructure，应用层经端口使用。identity 是所有上下文的上游，不依赖其他上下文（AGENTS.md
 * “上下文依赖”）。
 */
// 允许依赖的模块（#136）：上下文只能依赖这些模块，以及它们 api 包公开的类型；方向见 AGENTS.md“上下文依赖”。
@ApplicationModule(allowedDependencies = {"shared", "cache", "cache :: typed", "cache :: kv"})
@NullMarked
package com.liming.rag.identity;

import org.jspecify.annotations.NullMarked;
import org.springframework.modulith.ApplicationModule;
