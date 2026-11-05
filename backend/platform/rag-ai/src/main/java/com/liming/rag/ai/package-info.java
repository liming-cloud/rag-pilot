/**
 * 外部 AI 系统适配：模型调用（Spring AI 的对话、嵌入）、重排客户端、模型连通测试和答案评判。文件解析在 knowledge 的
 * infrastructure（本地 PDFBox、POI，D-019），不在这里。
 *
 * <p>标记为开放模块，上下文应在自己的 infrastructure 层经本上下文的端口使用（D-054，迁移中）；这里只放对外部系统的调用，
 * 不放业务规则。
 * 密钥只从环境变量读取，任何日志都不能输出密钥或完整的请求体。
 */
@ApplicationModule(type = ApplicationModule.Type.OPEN)
@NullMarked
package com.liming.rag.ai;

import org.jspecify.annotations.NullMarked;
import org.springframework.modulith.ApplicationModule;
