package com.liming.rag.ai;

import java.util.List;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.preretrieval.query.transformation.RewriteQueryTransformer;

/**
 * 用 Spring AI 的 {@link RewriteQueryTransformer} 改写问题（#145）。框架的默认模板是英文、针对向量库的，这里换成中文模板：
 * 调用方的规则在前，原问题在后。规则里的花括号换成全角，免得被模板引擎当作占位符（行业包的提示由管理员编写）。
 */
final class SpringAiQueryRewriting implements QueryRewriting {

    /** 一句问题用不了这么多 token；遇换行就停，模型多写的解释不会被生成。 */
    static final int MAX_TOKENS = 64;

    private final OpenAiChatModels models;
    private final ModelRoutes routes;

    SpringAiQueryRewriting(OpenAiChatModels models, ModelRoutes routes) {
        this.models = models;
        this.routes = routes;
    }

    @Override
    public String rewrite(String instructions, String question) {
        var route = routes.resolve(ModelUsage.ANSWER);
        var options = models.options(route).mutate().maxTokens(MAX_TOKENS).stop(List.of("\n"));
        var transformer = RewriteQueryTransformer.builder()
                .chatClientBuilder(ChatClient.builder(models.model(route), models.observations(), null, null)
                        .defaultOptions(options))
                .promptTemplate(new PromptTemplate(template(instructions)))
                .targetSearchSystem("知识库检索")
                .build();
        try {
            var rewritten = transformer.transform(new Query(question)).text();
            return rewritten == null ? "" : rewritten.strip();
        } catch (RuntimeException e) {
            throw new ModelCallException("改写问题失败：对话模型服务暂时不可用", e);
        }
    }

    static String template(String instructions) {
        var safe = instructions.replace('{', '｛').replace('}', '｝');
        return safe.strip() + "\n\n用于：{target}\n原问题：{query}\n改写后的问题：";
    }
}
