package com.liming.rag.knowledge.api;

/** 一份文档的标识、所在知识库和文件名，供其他模块引用文档（如标准题的期望文档，#224）。 */
public record DocumentRef(long id, long knowledgeBaseId, String filename) {
}
