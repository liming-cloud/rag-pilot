/**
 * 外部 AI 系统适配：模型调用（Spring AI 的对话、嵌入）、重排客户端、模型连通测试，以及评测的评判模型用的指定模型文字生成。
 * 文件解析在 knowledge 的 infrastructure（本地 PDFBox、POI，D-019），不在这里。
 *
 * <p>根包只放能力接口（含由上下文实现的 {@code ModelRouting}），每种能力一个子包（契约类型用 {@code @NamedInterface} 导出，
 * 实现包内私有），共用的在 {@code common}。上下文只在自己的 {@code infrastructure/ai} 里使用，实现本上下文 application 按用途声明的端口，
 * 把 {@link com.liming.rag.ai.common.ModelCallException} 转成本上下文的异常（错误码不变）；api、web、application、domain 不引用这里的类型（D-054，
 * ArchitectureRulesTest 检查）。这里只放对外部系统的调用，不放业务规则。
 * 密钥只从环境变量读取，任何日志都不能输出密钥或完整的请求体。
 */
@NullMarked
package com.liming.rag.ai;

import org.jspecify.annotations.NullMarked;
