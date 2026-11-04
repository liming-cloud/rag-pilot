package com.liming.rag.knowledge.application;

/**
 * 入库管道的读取端口（D-019、D-020）：按文件类型本地解析，不调用外部服务，实现在 {@code infrastructure.parsing}。
 * 读出整篇正文，用 Markdown 标题（{@code #}）表示章节，供标题感知分块使用。
 */
public interface DocumentReaders {

    /** 能读取的扩展名（小写，不带点）。 */
    boolean supports(String extension);

    /**
     * @return 整篇正文
     * @throws IllegalArgumentException 不支持的文件类型
     * @throws DocumentParseException 文件损坏、加密、不是 UTF-8、没有文字时；message 是给管理员看的中文原因
     */
    String read(String filename, byte[] content);
}
