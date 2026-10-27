package com.liming.rag.knowledge.application;

import com.liming.rag.ai.DocumentReaders;
import com.liming.rag.knowledge.domain.Chunk;
import com.liming.rag.knowledge.domain.TextChunker;
import java.util.List;
import java.util.Map;
import org.springframework.ai.document.Document;
import org.springframework.ai.document.DocumentTransformer;

/**
 * 入库管道的分块步骤：把读取器读出的整篇 Document 按标题切成分块 Document，规则见 {@link TextChunker}。
 * 不用 Spring AI 的 TextSplitter，因为它只返回字符串，会丢掉章节路径。
 */
final class HeadingChunker implements DocumentTransformer {

    private final TextChunker chunker = new TextChunker();

    @Override
    public List<Document> apply(List<Document> documents) {
        return documents.stream()
                .flatMap(document -> chunker.chunk(titleOf(document), document.getText()).stream())
                .map(HeadingChunker::toDocument)
                .toList();
    }

    /** 把分块 Document 还原成领域对象，供写入步骤使用。 */
    static Chunk toChunk(Document document) {
        var metadata = document.getMetadata();
        return new Chunk((Integer) metadata.get(ChunkMetadata.ORDINAL), (String) metadata.get(ChunkMetadata.HEADING_PATH),
                document.getText(), (Integer) metadata.get(ChunkMetadata.TOKEN_COUNT));
    }

    private static Document toDocument(Chunk chunk) {
        return new Document(chunk.content(), Map.of(
                ChunkMetadata.ORDINAL, chunk.ordinal(),
                ChunkMetadata.HEADING_PATH, chunk.headingPath(),
                ChunkMetadata.TOKEN_COUNT, chunk.tokenCount()));
    }

    private static String titleOf(Document document) {
        var filename = (String) document.getMetadata().get(DocumentReaders.FILENAME);
        var dot = filename.lastIndexOf('.');
        return dot > 0 ? filename.substring(0, dot) : filename;
    }
}
