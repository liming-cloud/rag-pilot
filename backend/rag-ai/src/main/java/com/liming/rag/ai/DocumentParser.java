package com.liming.rag.ai;

/**
 * 把 PDF、Word 等二进制文档转成文本。输出用 Markdown 标题（{@code #}）表示章节，供标题感知分块使用。
 */
public interface DocumentParser {

    /** 能解析的扩展名（小写，不带点）。 */
    boolean supports(String extension);

    /**
     * @throws DocumentParseException 文件损坏、加密、提取不到文字时，message 是给管理员看的中文原因
     */
    String toText(String filename, byte[] content);
}
