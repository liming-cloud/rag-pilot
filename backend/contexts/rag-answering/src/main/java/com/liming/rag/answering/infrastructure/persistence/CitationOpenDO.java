package com.liming.rag.answering.infrastructure.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;
import org.jspecify.annotations.NullUnmarked;

/** answer_citation_open 的一行：某条回答的第几个引用在何时被点开。只在 infrastructure 包内读取。 */
@TableName("answer_citation_open")
@NullUnmarked
final class CitationOpenDO {

    @TableId(type = IdType.AUTO)
    Long id;
    Long answerId;
    Integer citationIndex;
    OffsetDateTime openedAt;
}
