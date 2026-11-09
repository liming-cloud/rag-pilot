/**
 * 数据问答的授权上下文：按用户或角色授权可调用的工具和可查看的站点 / 区域，并判定任意一次工具调用的数据范围
 * （D-056、D-058、D-059）。判定只写在 {@code application.scope} 一处，管理员同样不绕过。
 *
 * <p>其他模块只能使用 {@code api} 包里的接口和 record。站点、区域主数据不存库，由客户系统的只读工具提供。
 */
// 允许依赖的模块：数据问答只依赖 identity（账号、角色、审计），方向见 AGENTS.md“上下文依赖”。
@ApplicationModule(allowedDependencies = {"shared", "persistence", "cache", "cache :: typed", "ai", "identity :: api"})
@NullMarked
package com.liming.rag.data;

import org.jspecify.annotations.NullMarked;
import org.springframework.modulith.ApplicationModule;
