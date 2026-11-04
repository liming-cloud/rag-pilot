package com.liming.rag.ai;

import java.util.List;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;

/**
 * 用 {@link ChatClient} 改写问题（#145）：调用方的规则作系统提示词，员工的问题作用户消息，输出限一句话。
 *
 * <p>没有用框架的 {@code RewriteQueryTransformer}：它把规则和问题放进同一条用户消息，三轮评测里改写开始给问题加括号注明类别，
 * 错字、口语题的误拒从 0.7% 升到 2.9%（D-052），所以保留原来“系统提示词 + 用户消息”的写法。提示词按消息整段交给框架，
 * 不走模板渲染。
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
        var options = models.options(route).mutate().maxTokens(MAX_TOKENS).stop(List.of("\n")).build();
        var prompt = new Prompt(List.of(new SystemMessage(instructions), new UserMessage(question)), options);
        try {
            var rewritten = ChatClient.create(models.model(route), models.observations()).prompt(prompt).call()
                    .content();
            return rewritten == null ? "" : rewritten.strip();
        } catch (RuntimeException e) {
            throw new ModelCallException("改写问题失败：对话模型服务暂时不可用", e);
        }
    }
}
