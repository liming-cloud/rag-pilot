/**
 * 共享内核：所有模块都可以直接使用的值对象（租户、用户）。
 *
 * <p>标记为开放模块，因为它只放无业务含义的基础类型；任何带业务规则的类都不应放在这里。
 */
@ApplicationModule(type = ApplicationModule.Type.OPEN)
package com.liming.rag.common;

import org.springframework.modulith.ApplicationModule;
