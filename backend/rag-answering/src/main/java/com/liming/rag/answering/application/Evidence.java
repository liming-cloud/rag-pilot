package com.liming.rag.answering.application;

import com.liming.rag.answering.api.Citation;
import com.liming.rag.knowledge.api.SearchHit;
import java.util.HashMap;
import org.springframework.ai.document.Document;

/**
 * 证据在流程里用 Spring AI 的 {@link Document} 表示，元数据键和转换集中在这里。Document 只在 answering 内部使用，
 * 对外仍是 {@link Citation}。
 */
final class Evidence {

    static final String CHUNK_ID = "chunkId";
    static final String DOCUMENT_ID = "documentId";
    static final String DOCUMENT_NAME = "documentName";
    static final String HEADING_PATH = "headingPath";

    private Evidence() {
    }

    static Document of(SearchHit hit) {
        var metadata = new HashMap<String, Object>();
        metadata.put(CHUNK_ID, hit.chunkId());
        metadata.put(DOCUMENT_ID, hit.documentId());
        metadata.put(DOCUMENT_NAME, hit.documentName());
        metadata.put(HEADING_PATH, hit.headingPath() == null ? "" : hit.headingPath());
        return Document.builder().text(hit.content()).metadata(metadata).score(hit.score()).build();
    }

    static String documentName(Document document) {
        return (String) document.getMetadata().get(DOCUMENT_NAME);
    }

    static String headingPath(Document document) {
        return (String) document.getMetadata().get(HEADING_PATH);
    }

    static Citation citation(int index, Document document) {
        var metadata = document.getMetadata();
        return new Citation(index, (Long) metadata.get(CHUNK_ID), (Long) metadata.get(DOCUMENT_ID),
                documentName(document), headingPath(document), document.getText());
    }
}
