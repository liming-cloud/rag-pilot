package com.liming.rag.ai.generation;

import com.liming.rag.ai.TextGeneration;
import com.liming.rag.ai.common.AiProperties;
import com.liming.rag.ai.common.ModelCallException;
import com.liming.rag.ai.common.ModelCallFailures;
import com.liming.rag.ai.common.ModelRoute;
import com.liming.rag.ai.common.ModelRoutes;
import com.liming.rag.ai.common.ModelUsage;
import com.liming.rag.ai.common.OpenAiChatModels;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 用 Spring AI 的 {@link ChatClient} 调用 OpenAI 兼容的对话接口（#145）。不重试，失败直接告诉用户稍后再问（流式生成的备用请求见
 * {@link #stream}）。模型和 ChatClient 都带上观测注册表，框架自带的观测（{@code spring.ai.chat.client}、
 * {@code gen_ai.client.operation}、token 用量）由 Actuator 汇总成指标；提示词和回答原文不进观测。
 *
 * <p>提示词按消息整段交给 ChatClient，不走它的模板渲染：资料原文和员工的问题里可能有花括号。
 */
final class SpringAiTextGeneration implements TextGeneration {

    private static final Logger log = LoggerFactory.getLogger(SpringAiTextGeneration.class);

    private final OpenAiChatModels models;
    private final ModelRoutes routes;
    private final Duration hedgeAfter;
    /** 按地址和密钥缓存 ChatClient，与 {@link OpenAiChatModels} 的模型一一对应。 */
    private final Map<String, ChatClient> clients = new ConcurrentHashMap<>();

    SpringAiTextGeneration(AiProperties properties, OpenAiChatModels models, ModelRoutes routes) {
        this.models = models;
        this.routes = routes;
        this.hedgeAfter = properties.chat().hedgeAfter();
    }

    private OpenAiChatOptions options(ModelRoute route) {
        return models.options(route);
    }

    /** Spring AI 2.0 不会把单次调用的选项和默认选项合并（调用带了选项就只用它），所以从默认选项复制一份再改限制。 */
    private OpenAiChatOptions limited(ModelRoute route, Limits limits) {
        return options(route).mutate().maxTokens(limits.maxTokens()).stop(limits.stop()).build();
    }

    private ChatClient client(ModelRoute route) {
        return clients.computeIfAbsent(route.baseUrl() + "\n" + route.apiKey(),
                key -> ChatClient.create(models.model(route), models.observations()));
    }

    private ModelRoute route(Limits limits) {
        return routes.resolve(limits.tier() == Tier.LIGHT ? ModelUsage.LIGHT : ModelUsage.ANSWER);
    }

    @Override
    public String complete(String systemPrompt, String userMessage) {
        var route = routes.resolve(ModelUsage.ANSWER);
        return call(route, prompt(systemPrompt, userMessage, options(route)));
    }

    @Override
    public String complete(String systemPrompt, String userMessage, Limits limits) {
        var route = route(limits);
        return call(route, prompt(systemPrompt, userMessage, limited(route, limits)));
    }

    private String call(ModelRoute route, Prompt prompt) {
        try {
            var text = client(route).prompt(prompt).call().content();
            return text == null ? "" : text;
        } catch (RuntimeException e) {
            throw unavailable(route, e);
        }
    }

    /**
     * 在调用线程上阻塞到生成结束；调用方在虚拟线程里运行，不占用平台线程。
     *
     * <p>配置了 {@code hedgeAfter} 时，过了这么久还没出首字就再发一个备用请求，哪个先出字用哪个，另一个取消；
     * 第一个请求在此之前失败时，备用请求也起到一次重试的作用。服务商的同一个模型常常整体卡住（D-028），
     * 所以备用请求可以用另一个模型，甚至另一个服务商（“备用回答”用途，D-043）。
     */
    @Override
    public String stream(String systemPrompt, String userMessage, Consumer<String> onDelta) {
        return stream(systemPrompt, userMessage, onDelta, new CompletableFuture<Void>());
    }

    /** {@code stop} 完成时截断流（主请求和备用请求一起取消），返回已生成的部分。 */
    @Override
    public String stream(String systemPrompt, String userMessage, Consumer<String> onDelta, CompletionStage<?> stop) {
        var answer = routes.resolve(ModelUsage.ANSWER);
        var main = deltas(answer, prompt(systemPrompt, userMessage, options(answer)));
        var deltas = hedgeAfter == null ? main
                : Flux.firstWithValue(main, Mono.delay(hedgeAfter)
                        .map(tick -> routes.resolve(ModelUsage.HEDGE))
                        .doOnNext(hedge -> log.info("对话模型 {} 内没出首字，发出备用请求（{}）", hedgeAfter, hedge.model()))
                        .flatMapMany(hedge -> deltas(hedge, prompt(systemPrompt, userMessage, options(hedge)))));
        var text = new StringBuilder();
        try {
            // suppressCancel：流正常结束时 takeUntilOther 会取消这个 Mono，不能连带把停止信号也取消掉，
            // 否则调用方会把每一轮都当成员工停的。
            deltas.takeUntilOther(Mono.fromFuture(stop.toCompletableFuture(), true)).doOnNext(delta -> {
                text.append(delta);
                onDelta.accept(delta);
            }).blockLast();
        } catch (RuntimeException e) {
            throw unavailable(answer, e);
        }
        return text.toString();
    }

    /** 只保留有文字的片段，这样“先出字”指的是真正的首字，而不是只带角色的空片段。 */
    private Flux<String> deltas(ModelRoute route, Prompt prompt) {
        return client(route).prompt(prompt).stream().content().filter(delta -> !delta.isEmpty());
    }

    private static Prompt prompt(String systemPrompt, String userMessage, OpenAiChatOptions options) {
        return new Prompt(List.of(new SystemMessage(systemPrompt), new UserMessage(userMessage)), options);
    }

    private static ModelCallException unavailable(ModelRoute route, RuntimeException e) {
        return ModelCallFailures.failed("chat", route.model(), "生成回答失败：对话模型服务暂时不可用，请稍后重试", e);
    }
}
