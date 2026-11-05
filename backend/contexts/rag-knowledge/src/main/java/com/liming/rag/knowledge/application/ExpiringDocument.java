package com.liming.rag.knowledge.application;

import com.liming.rag.knowledge.domain.Validity;
import java.time.LocalDate;

/** @param validity EXPIRING 或 EXPIRED */
public record ExpiringDocument(long knowledgeBaseId, String knowledgeBaseName, long documentId, String filename,
        LocalDate expiresOn, Validity.State validity) {
}
