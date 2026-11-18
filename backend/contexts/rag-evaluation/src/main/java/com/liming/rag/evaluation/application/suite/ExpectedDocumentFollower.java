package com.liming.rag.evaluation.application.suite;

import com.liming.rag.evaluation.application.SuiteStore;
import com.liming.rag.knowledge.api.DocumentReplaced;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** 期望文档上传新版本后自动接到新版本（#224、D-070）：和替换在同一个事务里。 */
@Component
class ExpectedDocumentFollower {

    private static final Logger log = LoggerFactory.getLogger(ExpectedDocumentFollower.class);

    private final SuiteStore store;

    ExpectedDocumentFollower(SuiteStore store) {
        this.store = store;
    }

    @EventListener
    void on(DocumentReplaced event) {
        int questions = store.followReplacement(event.tenantId(), event.replacedDocumentId(), event.documentId());
        if (questions > 0) {
            log.info("event=golden_question_followed_new_version document={} questions={}", event.documentId(),
                    questions);
        }
    }
}
