/**
 * 外部 AI 系统适配：模型调用（Spring AI）、重排客户端、docling-serve 解析客户端。
 *
 * <p>标记为开放模块，供 knowledge、answering 直接使用；这里只放对外部系统的调用，不放业务规则。
 * 密钥只从环境变量读取，任何日志都不能输出密钥或完整的请求体。
 */
@ApplicationModule(type = ApplicationModule.Type.OPEN)
package com.liming.rag.ai;

import org.springframework.modulith.ApplicationModule;
