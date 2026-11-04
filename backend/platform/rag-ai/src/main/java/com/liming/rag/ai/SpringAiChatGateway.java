package com.liming.rag.ai;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** 通过 Spring AI 调用 OpenAI 兼容的对话接口。不重试，失败直接告诉用户稍后再问（流式生成的备用请求见 {@link #stream}）。 */
final class SpringAiChatGateway implements ChatGateway {

    private static final Logger log = LoggerFactory.getLogger(SpringAiChatGateway.class);

    /** 不需要密钥的服务（如内网自建的 vLLM）也要给 OpenAI 客户端一个值。 */
    private static final String NO_KEY = "EMPTY";

    private final AiProperties properties;
    private final ModelRoutes routes;
    private final Duration hedgeAfter;
    /** 按地址和密钥缓存客户端：换模型只换调用选项，换服务商才新建客户端。 */
    private final Map<String, ChatModel> models = new ConcurrentHashMap<>();

    SpringAiChatGateway(AiProperties properties, ModelRoutes routes) {
        properties.requireApiKey();
        this.properties = properties;
        this.routes = routes;
        this.hedgeAfter = properties.chat().hedgeAfter();
    }

    /** 每次调用都按当前配置取服务商和模型，管理端保存后下一次请求就生效（D-043）。 */
    private OpenAiChatOptions options(ModelRoute route) {
        return OpenAiChatOptions.builder()
                .baseUrl(route.baseUrl())
                .apiKey(route.apiKey() == null ? NO_KEY : route.apiKey())
                .model(route.model())
                .temperature(properties.chat().temperature())
                .maxTokens(properties.chat().maxTokens())
                .timeout(properties.timeout())
                .maxRetries(0)
                .build();
    }

    private ChatModel model(ModelRoute route) {
        return models.computeIfAbsent(route.baseUrl() + "\n" + route.apiKey(),
                key -> OpenAiChatModel.builder().options(options(route)).build());
    }

    @Override
    public String complete(String systemPrompt, String userMessage) {
        var route = routes.resolve(ModelUsage.ANSWER);
        try {
            var response = model(route).call(prompt(systemPrompt, userMessage, options(route)));
            var text = response.getResult().getOutput().getText();
            return text == null ? "" : text;
        } catch (RuntimeException e) {
            throw unavailable(e);
        }
    }

    /**
     * Spring AI 2.0 不会把单次调用的选项和默认选项合并（调用带了选项就只用它），所以从默认选项复制一份再改限制。
     */
    @Override
    public String complete(String systemPrompt, String userMessage, Limits limits) {
        var route = routes.resolve(limits.tier() == Tier.LIGHT ? ModelUsage.LIGHT : ModelUsage.ANSWER);
        var limited = options(route).mutate().maxTokens(limits.maxTokens()).stop(limits.stop()).build();
        try {
            var response = model(route).call(prompt(systemPrompt, userMessage, limited));
            var text = response.getResult().getOutput().getText();
            return text == null ? "" : text;
        } catch (RuntimeException e) {
            throw unavailable(e);
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
        var main = deltas(model(answer), prompt(systemPrompt, userMessage, options(answer)));
        var deltas = hedgeAfter == null ? main
                : Flux.firstWithValue(main, Mono.delay(hedgeAfter)
                        .map(tick -> routes.resolve(ModelUsage.HEDGE))
                        .doOnNext(hedge -> log.info("对话模型 {} 内没出首字，发出备用请求（{}）", hedgeAfter, hedge.model()))
                        .flatMapMany(hedge -> deltas(model(hedge),
                                prompt(systemPrompt, userMessage, options(hedge)))));
        var text = new StringBuilder();
        try {
            // suppressCancel：流正常结束时 takeUntilOther 会取消这个 Mono，不能连带把停止信号也取消掉，
            // 否则调用方会把每一轮都当成员工停的。
            deltas.takeUntilOther(Mono.fromFuture(stop.toCompletableFuture(), true)).doOnNext(delta -> {
                text.append(delta);
                onDelta.accept(delta);
            }).blockLast();
        } catch (RuntimeException e) {
            throw unavailable(e);
        }
        return text.toString();
    }

    /** 只保留有文字的片段，这样“先出字”指的是真正的首字，而不是只带角色的空片段。 */
    private static Flux<String> deltas(ChatModel model, Prompt prompt) {
        return model.stream(prompt)
                .mapNotNull(response -> response.getResult() == null ? null : response.getResult().getOutput().getText())
                .filter(delta -> !delta.isEmpty());
    }

    private static Prompt prompt(String systemPrompt, String userMessage, OpenAiChatOptions options) {
        return new Prompt(List.of(new SystemMessage(systemPrompt), new UserMessage(userMessage)), options);
    }

    private static ModelCallException unavailable(RuntimeException e) {
        return new ModelCallException("生成回答失败：对话模型服务暂时不可用，请稍后重试", e);
    }
}
