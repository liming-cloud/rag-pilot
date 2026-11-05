package com.liming.rag.answering.infrastructure.persistence;

import com.liming.rag.answering.application.review.CitationOpens;
import java.time.OffsetDateTime;
import org.springframework.stereotype.Repository;

@Repository
class MybatisCitationOpens implements CitationOpens {

    private final CitationOpenMapper mapper;

    MybatisCitationOpens(CitationOpenMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void record(long answerId, int citationIndex, OffsetDateTime openedAt) {
        var row = new CitationOpenDO();
        row.answerId = answerId;
        row.citationIndex = citationIndex;
        row.openedAt = openedAt;
        mapper.insert(row);
    }
}
