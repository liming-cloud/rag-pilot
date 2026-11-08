package com.liming.rag.answering.infrastructure.persistence;

import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;
import org.jspecify.annotations.NullUnmarked;

/** answer_data_citation 的一行：回答引用的一条数据出处（主键是两列，没有 id）。只在 infrastructure 包内读取。 */
@TableName("answer_data_citation")
@NullUnmarked
final class DataCitationDO {

    Long answerId;
    Integer citationIndex;
    String tool;
    String sourceSystem;
    String dataset;
    String conditions;
    OffsetDateTime asOf;
    OffsetDateTime queriedAt;
    Boolean truncated;

    Long getAnswerId() {
        return answerId;
    }
}
