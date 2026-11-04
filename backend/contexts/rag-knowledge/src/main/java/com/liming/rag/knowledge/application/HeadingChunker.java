package com.liming.rag.knowledge.application;

import com.liming.rag.knowledge.domain.Chunk;
import com.liming.rag.knowledge.domain.TextChunker;
import java.util.List;

/**
 * 入库管道的分块步骤：把读出的整篇正文按标题切成分块，章节路径以文件名（去掉扩展名）开头，规则见 {@link TextChunker}。
 */
final class HeadingChunker {

    private final TextChunker chunker = new TextChunker();

    List<Chunk> chunk(String filename, String text) {
        return chunker.chunk(titleOf(filename), text);
    }

    private static String titleOf(String filename) {
        var dot = filename.lastIndexOf('.');
        return dot > 0 ? filename.substring(0, dot) : filename;
    }
}
