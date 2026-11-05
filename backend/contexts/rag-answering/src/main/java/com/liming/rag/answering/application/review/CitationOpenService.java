package com.liming.rag.answering.application.review;

import com.liming.rag.answering.application.AnsweringException;
import com.liming.rag.answering.domain.AnswerRecordRepository;
import com.liming.rag.answering.domain.Citation;
import com.liming.rag.shared.TenantId;
import com.liming.rag.shared.UserId;
import java.time.Clock;
import java.time.OffsetDateTime;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 员工点开引用（#145 A11）：记下哪条回答的第几个引用，用来统计引用点开率。 */
@Service
public class CitationOpenService {

    private final AnswerRecordRepository records;
    private final CitationOpens opens;
    private final Clock clock;

    CitationOpenService(AnswerRecordRepository records, CitationOpens opens, Clock clock) {
        this.records = records;
        this.opens = opens;
        this.clock = clock;
    }

    /** @throws AnsweringException 回答不存在、不是这位用户问的，或没有这个引用位置时为 NOT_FOUND */
    @Transactional
    public void opened(TenantId tenantId, UserId by, long answerId, int citationIndex) {
        var record = records.find(tenantId, answerId).filter(found -> found.asker().equals(by))
                .orElseThrow(CitationOpenService::notFound);
        if (record.reply().citations().stream().map(Citation::index).noneMatch(index -> index == citationIndex)) {
            throw notFound();
        }
        opens.record(answerId, citationIndex, OffsetDateTime.now(clock));
    }

    private static AnsweringException notFound() {
        return new AnsweringException(AnsweringException.Reason.NOT_FOUND, "回答或引用不存在");
    }
}
