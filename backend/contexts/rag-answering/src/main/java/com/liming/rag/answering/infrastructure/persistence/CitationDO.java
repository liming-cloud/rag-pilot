package com.liming.rag.answering.infrastructure.persistence;

import com.baomidou.mybatisplus.annotation.TableName;
import org.jspecify.annotations.NullUnmarked;

/** answer_citation 的一行：回答引用的一条出处（主键是两列，没有 id）。只在 infrastructure 包内读取。 */
@TableName("answer_citation")
@NullUnmarked
final class CitationDO {

    Long answerId;
    Integer citationIndex;
    Long chunkId;
    Long documentId;
    String documentName;
    String headingPath;
}
