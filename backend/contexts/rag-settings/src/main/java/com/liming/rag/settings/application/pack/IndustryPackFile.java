package com.liming.rag.settings.application.pack;

import com.liming.rag.settings.application.SettingKey;

import com.liming.rag.settings.api.IndustryPack;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.boot.context.properties.source.ConfigurationPropertyName;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;

/**
 * 行业包文件 {@code industry-packs/<名字>/pack.yml}（D-045），随代码打包。内容按下面的 record 绑定，没写的项用内核默认值；
 * 读到时就校验，不合格时启动失败，不带着坏的行业包运行。
 *
 * @param name 显示名称
 * @param answer 回答提示词里的角色、引用示例和拒答话术
 * @param rewrite 低分改写提示词里的角色和行业提示
 * @param chat 问答页的占位、空状态说明、推荐问题，以及答不上来时的建议和联系话术（后两项不能在界面改）
 * @param markers 行业标志词：内核代码里不能出现（{@code KernelNeutralityTest}），不参与检索
 * @param vocabulary 行业词表
 */
public record IndustryPackFile(String name, @DefaultValue Answer answer, @DefaultValue Rewrite rewrite,
        @DefaultValue Chat chat, List<String> markers, List<IndustryPack.Term> vocabulary) {

    public static final String LOCATION = "industry-packs/%s/pack.yml";

    public record Answer(String role, String citationExample, String refusal) {
    }

    public record Rewrite(String role, List<String> hints) {
    }

    public record Chat(String placeholder, String emptyHint, List<String> suggestedQuestions,
            List<String> refusalTips, String refusalContact) {
    }

    public IndustryPackFile {
        markers = markers == null ? List.of() : List.copyOf(markers);
        vocabulary = vocabulary == null ? List.of() : List.copyOf(vocabulary);
    }

    /** 读出并校验；找不到或不合格时抛 {@link IllegalStateException}，说明是哪个行业包、哪一项。 */
    public static IndustryPackFile load(String code) {
        var resource = new ClassPathResource(LOCATION.formatted(code));
        if (!resource.exists()) {
            throw new IllegalStateException("找不到行业包 " + code + "：classpath 下没有 " + LOCATION.formatted(code)
                    + "，检查 RAG_INDUSTRY_PACK");
        }
        try {
            var sources = new YamlPropertySourceLoader().load(code, resource);
            var file = new Binder(ConfigurationPropertySources.from(sources))
                    .bindOrCreate(ConfigurationPropertyName.EMPTY, Bindable.of(IndustryPackFile.class), null);
            file.validate(code);
            return file;
        } catch (IOException e) {
            throw new UncheckedIOException("读取行业包 " + code + " 失败", e);
        }
    }

    /** 行业包里影响检索和回答的部分；没写的用内核默认值。 */
    public IndustryPack pack(String code) {
        return new IndustryPack(code, name, or(answer.role(), KernelDefaults.ANSWER_ROLE),
                or(answer.citationExample(), KernelDefaults.CITATION_EXAMPLE),
                or(rewrite.role(), KernelDefaults.REWRITE_ROLE),
                rewrite.hints() == null ? List.of() : rewrite.hints(), vocabulary);
    }

    /** 某一项在行业包里的值；行业包没写时为空，由调用方退回内核默认值。品牌不归行业包。 */
    public Optional<List<String>> value(SettingKey key) {
        return Optional.ofNullable(switch (key) {
            case CHAT_PLACEHOLDER -> text(chat.placeholder());
            case CHAT_EMPTY_HINT -> text(chat.emptyHint());
            case SUGGESTED_QUESTIONS -> chat.suggestedQuestions();
            case REFUSAL_MESSAGE -> text(answer.refusal());
            default -> null;
        });
    }

    private void validate(String code) {
        require(name != null && !name.isBlank() && name.length() <= 20, code, "name 不能为空，最多 20 个字");
        validatePrompts(code);
        for (var key : SettingKey.values()) {
            value(key).ifPresent(value -> SettingRules.problem(key, value).ifPresent(problem ->
                    require(false, code, key + "：" + problem)));
        }
        validateChat(code);
        markers.forEach(marker -> require(marker != null && !marker.isBlank(), code, "markers 不能有空词"));
        validateVocabulary(code);
    }

    private void validatePrompts(String code) {
        for (var text : new String[] {answer.role(), answer.citationExample(), rewrite.role()}) {
            require(text == null || (!text.isBlank() && text.length() <= 200), code, "提示词里的角色和示例不能为空，最多 200 个字");
        }
        require(answer.citationExample() == null || answer.citationExample().matches(".*\\[\\d+]。?$"), code,
                "answer.citation-example 要以 [数字] 结尾，如 “……[2]。”");
        if (rewrite.hints() != null) {
            rewrite.hints().forEach(hint -> require(hint != null && !hint.isBlank() && hint.length() <= 100, code,
                    "rewrite.hints 每条不能为空，最多 100 个字"));
        }
    }

    private void validateChat(String code) {
        if (chat.refusalTips() != null) {
            require(chat.refusalTips().size() <= 3, code, "chat.refusal-tips 最多 3 条");
            chat.refusalTips().forEach(tip -> require(tip != null && !tip.isBlank() && tip.length() <= 60, code,
                    "chat.refusal-tips 每条不能为空，最多 60 个字"));
        }
        require(chat.refusalContact() == null || chat.refusalContact().length() <= 100, code,
                "chat.refusal-contact 最多 100 个字");
    }

    private void validateVocabulary(String code) {
        var seen = new HashSet<String>();
        for (var term : vocabulary) {
            require(term.term() != null && !term.term().isBlank() && !term.members().isEmpty(), code,
                    "vocabulary 每组要有 term 和至少一个 members");
            require(seen.add(term.term()), code, "vocabulary 的 term 重复：" + term.term());
            term.members().forEach(member -> require(member != null && !member.isBlank()
                    && !member.equals(term.term()), code, "vocabulary“" + term.term() + "”的 members 有空词或与 term 相同"));
        }
    }

    private static List<String> text(String value) {
        return value == null ? null : List.of(value);
    }

    private static String or(String value, String fallback) {
        return value == null ? fallback : value.strip();
    }

    private static void require(boolean condition, String code, String message) {
        if (!condition) {
            throw new IllegalStateException("行业包 " + code + " 不合格：" + message);
        }
    }
}
