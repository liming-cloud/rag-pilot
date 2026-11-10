package com.liming.rag.knowledge.domain;

import com.liming.rag.shared.ErrorKind;
import java.io.Serial;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;

/**
 * 一份上传的文件（#136）：文件名、按扩展名确定的类型、大小和内容摘要。能不能收下由这里判断，不在应用服务里各写一份。
 * 不保留文件内容，内容由应用层存进文件存储。
 *
 * @param sha256 内容的 SHA-256（十六进制），同一个库里不能重复，原始文件也按它存放
 */
public record UploadedFile(String filename, String mediaType, String sha256, long sizeBytes) {

    /** 单个文件上限 50 MB，与 multipart 配置一致（rag-app 的 application.yml）。 */
    public static final long MAX_SIZE_BYTES = 50L * 1024 * 1024;

    /** 文件名长度上限，与数据库列宽一致。 */
    public static final int MAX_FILENAME_LENGTH = 255;

    /** 支持的扩展名（小写）和对应的类型：UTF-8 的 TXT、Markdown，PDF、Word（.docx）、Excel（.xlsx / .xls）。 */
    private static final Map<String, String> SUPPORTED_TYPES = Map.of(
            "txt", "text/plain",
            "md", "text/markdown",
            "markdown", "text/markdown",
            "pdf", "application/pdf",
            "docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "xls", "application/vnd.ms-excel");

    /** 文件类型不支持。和其他不合格的情况分开，接口按“不支持的类型”报错（{@code KNOWLEDGE_UNSUPPORTED_TYPE}）。 */
    public static final class UnsupportedTypeException extends KnowledgeRuleException {

        @Serial
        private static final long serialVersionUID = 1L;

        UnsupportedTypeException() {
            super(ErrorKind.UNSUPPORTED, "UNSUPPORTED_TYPE",
                    "目前支持 PDF、Word（.docx）、Excel（.xlsx / .xls）、TXT 和 Markdown；旧版 .doc 请先另存为 .docx");
        }
    }

    /**
     * 收下一份文件。
     *
     * @throws UnsupportedTypeException 扩展名不支持
     * @throws KnowledgeRuleException 文件名太长、文件是空的或超过 50 MB
     */
    public static UploadedFile of(String filename, byte[] content) {
        if (filename != null && filename.length() > MAX_FILENAME_LENGTH) {
            throw KnowledgeRuleException.invalid("文件名不能超过 " + MAX_FILENAME_LENGTH + " 个字符");
        }
        var dot = filename == null ? -1 : filename.lastIndexOf('.');
        var mediaType = SUPPORTED_TYPES.get(dot < 0 ? "" : filename.substring(dot + 1).toLowerCase(Locale.ROOT));
        if (mediaType == null) {
            throw new UnsupportedTypeException();
        }
        if (content.length == 0) {
            throw KnowledgeRuleException.invalid("文件是空的");
        }
        if (content.length > MAX_SIZE_BYTES) {
            throw KnowledgeRuleException.invalid("单个文件不能超过 50 MB");
        }
        return new UploadedFile(filename, mediaType, sha256(content), content.length);
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("当前 JDK 不支持 SHA-256", e);
        }
    }
}
