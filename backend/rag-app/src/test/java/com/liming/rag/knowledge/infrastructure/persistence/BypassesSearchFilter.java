package com.liming.rag.knowledge.infrastructure.persistence;

/**
 * 违规样例：MybatisChunkIndex 之外直接调用带成员过滤的检索 SQL。ChunkSearchMapper 是包内可见的，所以样例放在同一个包。
 */
public class BypassesSearchFilter {

    Object search(ChunkSearchMapper mapper) {
        return mapper.keyword(1L, 1L, null, "冷柜", 10);
    }
}
