package com.liming.rag.knowledge.application;

import java.util.List;

/** @param documents 最早到期的若干份（已失效、即将到期，按失效日期先后），供运营概览和周报列出 */
public record ExpirySummary(long expiring, long expired, List<ExpiringDocument> documents) {
}
