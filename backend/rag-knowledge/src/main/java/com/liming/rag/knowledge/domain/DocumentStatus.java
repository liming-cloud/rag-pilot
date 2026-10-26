package com.liming.rag.knowledge.domain;

/**
 * 文档处理状态。TXT、Markdown 在上传请求内同步处理；接入 docling 后增加排队状态，改为异步处理。
 */
public enum DocumentStatus {
    /** 已接收，正在解析和分块。 */
    PARSING,
    /** 已分块入库，可以检索。 */
    READY,
    /** 处理失败，原因写在 error 字段，需要人工处理。 */
    FAILED
}
