package com.liming.rag.evaluation.application.suite;

import java.util.List;

/**
 * CSV 里的一行（前端解析，#224）：知识库、期望文档按名称填，期望文档为空表示应当拒答。
 *
 * @param line 文件里的行号，出错时提示用
 * @param match ALL 或 ANY
 */
public record ImportRow(int line, String question, String knowledgeBase, List<String> documents, String match) {
}
