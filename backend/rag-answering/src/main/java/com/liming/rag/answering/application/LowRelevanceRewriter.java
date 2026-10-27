package com.liming.rag.answering.application;

import com.liming.rag.ai.ChatGateway;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.preretrieval.query.transformation.QueryTransformer;

/**
 * 把员工的问题改写成规范说法，供“低分时再检索一次”使用。实现 Spring AI 的 {@link QueryTransformer}，
 * 但模型调用走 rag-ai 的 {@link ChatGateway}，不在这里直接建 ChatClient。
 *
 * <p>提示词只写通用规则，不针对任何评测题。模型没有给出有效改写（空、NO_ANSWER、太长、和原问题一样）时返回原查询。
 */
final class LowRelevanceRewriter implements QueryTransformer {

    static final String SYSTEM = """
            你负责把连锁门店员工的提问改写成便于检索门店制度文件的规范问题。
            规则：
            1. 纠正错别字和同音字。
            2. 把口语、方言、简称改成书面说法。
            3. 提到具体商品时，在后面补上它所属的商品类别。
            4. 不要回答问题，不要添加原问题里没有的条件。
            5. 只输出改写后的一句问题，不要解释。
            """;

    private final ChatGateway chat;

    LowRelevanceRewriter(ChatGateway chat) {
        this.chat = chat;
    }

    @Override
    public Query transform(Query query) {
        var reply = chat.complete(SYSTEM, query.text());
        var rewritten = reply == null ? "" : reply.strip().lines().findFirst().orElse("").strip()
                .replaceAll("^[\"“”'「」]+|[\"“”'「」]+$", "");
        if (rewritten.isEmpty() || rewritten.startsWith(AnswerPrompt.NO_ANSWER)
                || rewritten.length() > AnswerService.MAX_QUESTION_LENGTH || rewritten.equals(query.text())) {
            return query;
        }
        return query.mutate().text(rewritten).build();
    }
}
