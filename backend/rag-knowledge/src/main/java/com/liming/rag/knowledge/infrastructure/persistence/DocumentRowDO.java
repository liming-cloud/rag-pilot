package com.liming.rag.knowledge.infrastructure.persistence;

/** 文档列表的一行（#136）：document 表的列，另带被替换文档的文件名，只在 infrastructure 包内读取。 */
final class DocumentRowDO extends DocumentDO {

    String replacesFilename;
}
