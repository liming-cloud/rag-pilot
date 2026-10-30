/**
 * 共享内核：多个上下文共同依赖、只用纯 Java 表达、很少变动的模型概念——租户和用户标识、业务错误分类、聚合根标记。
 *
 * <p>只依赖 JDK，不放框架代码、配置、数据库迁移或任何业务规则；改动它等于改动所有模块的合同，新增类型前先在 DECISIONS.md 记录理由。
 * 标记为开放模块，所有模块都可以直接使用。
 */
@ApplicationModule(type = ApplicationModule.Type.OPEN)
package com.liming.rag.shared;

import org.springframework.modulith.ApplicationModule;
