/**
 * 核心限界上下文：知识库、文档、分块、成员授权、混合检索。
 *
 * <p>其他模块只能使用 {@code api} 包里的接口和 record，或监听本模块发布的领域事件。
 * domain 包放聚合与领域事件；MyBatis-Plus 的 DO、Wrapper、Page 只允许出现在 infrastructure 包。
 */
package com.liming.rag.knowledge;
