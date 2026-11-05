/**
 * MyBatis-Plus 平台组件（D-053）：全局配置（Mapper XML 的位置、分页插件）和分页结果与 {@link com.liming.rag.shared.Paged}
 * 之间的转换。各上下文只在自己的 infrastructure 包里用 MyBatis-Plus 的类型；SQL 写在各自的 Mapper XML，单表读写用
 * BaseMapper 和 Lambda 条件。标记为开放模块，上下文的 infrastructure 直接使用。
 */
@ApplicationModule(type = ApplicationModule.Type.OPEN)
@NullMarked
package com.liming.rag.persistence;

import org.jspecify.annotations.NullMarked;
import org.springframework.modulith.ApplicationModule;
