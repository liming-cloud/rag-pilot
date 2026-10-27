package com.liming.rag.ai;

import org.springframework.ai.document.DocumentReader;

/**
 * 按文件类型创建 Spring AI 的 {@link DocumentReader}。读出一个 Document，正文用 Markdown 标题（{@code #}）
 * 表示章节，供标题感知分块使用；元数据里带 {@link #FILENAME}。
 */
public interface DocumentReaders {

    /** 元数据键：原始文件名。 */
    String FILENAME = "filename";

    /** 能读取的扩展名（小写，不带点）。 */
    boolean supports(String extension);

    /**
     * 返回的 reader 在 {@code get()} 时才解析；文件损坏、加密、不是 UTF-8、没有文字时抛
     * {@link DocumentParseException}，message 是给管理员看的中文原因。
     */
    DocumentReader forFile(String filename, byte[] content);
}
