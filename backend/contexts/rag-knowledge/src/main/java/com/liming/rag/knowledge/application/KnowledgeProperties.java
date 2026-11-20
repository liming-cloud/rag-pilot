package com.liming.rag.knowledge.application;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 知识库配置（{@code rag.knowledge}）。
 *
 * @param ingestion 后台入库
 * @param storage   原始文件存储
 * @param expiryWarningDays 失效日期在今天起多少天内算“即将到期”，管理端据此提醒（#124）
 * @param chunking  分块参数
 * @param ocr       图片文字识别
 */
@ConfigurationProperties("rag.knowledge")
public record KnowledgeProperties(@DefaultValue Ingestion ingestion, @DefaultValue Storage storage,
        @DefaultValue("30") int expiryWarningDays, @DefaultValue Chunking chunking, @DefaultValue Ocr ocr) {

    public KnowledgeProperties {
        if (expiryWarningDays < 1 || expiryWarningDays > 365) {
            throw new IllegalArgumentException("rag.knowledge.expiry-warning-days 必须在 1 到 365 之间");
        }
    }

    /** @param concurrency 后台同时处理的文档数，限制对嵌入模型的并发调用 */
    public record Ingestion(@DefaultValue("2") int concurrency) {

        public Ingestion {
            if (concurrency < 1 || concurrency > 32) {
                throw new IllegalArgumentException("rag.knowledge.ingestion.concurrency 必须在 1 到 32 之间");
            }
        }
    }

    /** @param root 原始文件存放目录；Compose 中挂载为数据卷 */
    public record Storage(@DefaultValue("./data/files") String root) {

        public Storage {
            if (root == null || root.isBlank()) {
                throw new IllegalArgumentException("rag.knowledge.storage.root 不能为空");
            }
        }
    }

    /**
     * 分块参数（#225）：单块的 token 上限和相邻分块的重叠，默认值由评测对比选定（D-073）。
     * 改了只影响之后入库的资料；评测时用它比较不同参数。
     */
    public record Chunking(@DefaultValue("500") int maxTokens, @DefaultValue("80") int overlapTokens) {

        public Chunking {
            if (maxTokens < 50 || maxTokens > 4000 || overlapTokens < 0 || overlapTokens >= maxTokens) {
                throw new IllegalArgumentException(
                        "rag.knowledge.chunking.max-tokens 必须在 50 到 4000 之间，overlap-tokens 不小于 0 且小于 max-tokens");
            }
        }
    }

    /** @param url RapidOCR 服务地址（#223）；为空时不识别图片里的文字 */
    public record Ocr(@DefaultValue("") String url) {
    }
}
