package com.liming.rag.answering.application.review;

import java.time.OffsetDateTime;

/** 点开引用的记录端口：只记哪条回答的第几个引用和时间。 */
public interface CitationOpens {

    void record(long answerId, int citationIndex, OffsetDateTime openedAt);
}
