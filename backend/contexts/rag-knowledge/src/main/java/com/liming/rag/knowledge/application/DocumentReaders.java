package com.liming.rag.knowledge.application;

import com.liming.rag.knowledge.application.ingestion.DocumentParseException;

/**
 * 入库管道的读取端口（D-019、D-020）：按文件类型本地解析，实现在 {@code infrastructure.parsing}；
 * 图片里的文字交给部署里的识别服务（#223、D-074）。
 * 读出整篇正文，用 Markdown 标题（{@code #}）表示章节，供标题感知分块使用。
 */
public interface DocumentReaders {

    /** 能读取的扩展名（小写，不带点）。 */
    boolean supports(String extension);

    /**
     * 读出的结果。
     *
     * @param content 整篇正文
     * @param images 图片数（Word、PDF、PowerPoint），资料体检用来判断内容是否主要在图片里（#218）；其他格式为 0
     * @param largestTableRows 最大一张表格（Excel 的一个工作表）的数据行数，不含表头；没有表格为 0
     * @param imageTextPending 有图片没识别：识别服务没配置或暂时不可用，以后由重新分块补上（#223）
     */
    record Text(String content, int images, int largestTableRows, boolean imageTextPending) {
    }

    /** 是否配置了图片文字识别服务。 */
    boolean recognizesImages();

    /**
     * @return 整篇正文和资料体检用的信号
     * @throws IllegalArgumentException 不支持的文件类型
     * @throws DocumentParseException 文件损坏、加密、不是 UTF-8、没有文字时；message 是给管理员看的中文原因
     */
    Text read(String filename, byte[] content);
}
