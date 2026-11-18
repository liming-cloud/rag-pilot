package com.liming.rag.evaluation.application;

/**
 * 标准题期望的一份文档。存文档而不存分块：重新分块后照样有效（D-069）。
 *
 * @param filename 保存时的文件名，文档删除后仍能显示期望的是哪份
 */
public record ExpectedDocument(long documentId, String filename) {
}
