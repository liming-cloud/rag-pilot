package com.liming.rag;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 日志格式（#198，B-06）：每条日志都是 {@code event=snake_case 键=值}，Logger 统一叫 {@code log}，代码里的事件都列在运维手册
 * {@code deploy/README.md} 里。扫描源码而不用 ArchUnit：字节码里看不到日志的格式串，{@code event=} 前缀只能从源码查。
 */
class LogFormatTest {

    private static final Pattern LOGGER_FIELD = Pattern.compile("\\bLogger\\s+(\\w+)\\s*=");
    /** 日志调用和它的第一个参数（到第一个逗号或右括号为止）。 */
    private static final Pattern LOG_CALL = Pattern.compile(
            "\\b(\\w+)\\.(trace|debug|info|warn|error)\\(\\s*(\"(?:[^\"\\\\]|\\\\.)*\"|[^,)]*)");
    /** {@code event=名字}，后面每一项都是 {@code 键=值}，键用英文。 */
    private static final Pattern FORMAT = Pattern.compile(
            "\"event=([a-z][a-z0-9_]*)((?: [a-zA-Z][a-zA-Z0-9_]*=[^ \"]+)*)\"");
    private static final Set<String> LOGGER_NAMES = Set.of("log", "LOG", "logger", "LOGGER");

    @Test
    @DisplayName("后端代码的日志都是 event= 格式，Logger 叫 log，事件都在运维手册里")
    void productionLogsFollowFormat() throws IOException {
        Path backend = Path.of("../..").toRealPath();
        var events = new TreeSet<String>();
        var problems = new ArrayList<String>();
        try (var files = Files.walk(backend)) {
            for (Path file : files.filter(LogFormatTest::isMainSource).toList()) {
                var name = backend.relativize(file).toString();
                problems(Files.readString(file, StandardCharsets.UTF_8), events)
                        .forEach(problem -> problems.add(name + "：" + problem));
            }
        }
        assertThat(events).as("应扫描到日志事件").hasSizeGreaterThan(20);
        String manual = Files.readString(backend.resolve("../deploy/README.md"), StandardCharsets.UTF_8);
        events.stream().filter(event -> !manual.contains("event=" + event + "`")
                        && !manual.contains("event=" + event + " "))
                .forEach(event -> problems.add("运维手册 deploy/README.md 没有列出事件 event=" + event));
        assertThat(problems).isEmpty();
    }

    @Test
    @DisplayName("中文自由文本、缺键名、非 log 命名、日志串不是字面量都会被指出")
    void detectsViolations() {
        var events = new TreeSet<String>();
        assertThat(problems("""
                private static final Logger LOG = LoggerFactory.getLogger(X.class);
                void a() {
                    log.info("清理了 {} 条记录", deleted);
                    log.warn("event=Bad-Name user={}", id);
                    log.warn("event=access_denied user={} {}", id, missing);
                    log.warn("event=usage_failed 原因={}", e);
                    log.error(failure, e);
                }
                """, events))
                .anyMatch(p -> p.contains("LOG"))
                .anyMatch(p -> p.contains("清理了"))
                .anyMatch(p -> p.contains("Bad-Name"))
                .anyMatch(p -> p.contains("user={} {}"))
                .anyMatch(p -> p.contains("原因"))
                .anyMatch(p -> p.contains("failure"));
    }

    @Test
    @DisplayName("合格的日志没有问题，并记下事件名")
    void acceptsEventLogs() {
        var events = new TreeSet<String>();
        assertThat(problems("""
                private static final Logger log = LoggerFactory.getLogger(X.class);
                void a() {
                    log.warn("event=cache_failure cache={} op={} retry_after={}s", cache, op, seconds);
                    log.info(
                            "event=document_deferred document={}", id);
                    log.error("event=unexpected_error", e);
                }
                """, events)).isEmpty();
        assertThat(events).containsExactly("cache_failure", "document_deferred", "unexpected_error");
    }

    private static boolean isMainSource(Path file) {
        var path = file.toString().replace('\\', '/');
        return path.endsWith(".java") && path.contains("/src/main/java/") && !path.contains("/target/");
    }

    /** 一个源文件里的问题；合格日志的事件名加进 {@code events}。 */
    static List<String> problems(String source, Set<String> events) {
        var problems = new ArrayList<String>();
        var fields = LOGGER_FIELD.matcher(source);
        while (fields.find()) {
            if (!fields.group(1).equals("log")) {
                problems.add("Logger 应命名为 log，不是 " + fields.group(1));
            }
        }
        var calls = LOG_CALL.matcher(source);
        while (calls.find()) {
            if (!LOGGER_NAMES.contains(calls.group(1))) {
                continue;
            }
            var first = calls.group(3).strip();
            var format = FORMAT.matcher(first);
            if (format.matches()) {
                events.add(format.group(1));
            } else {
                problems.add("日志应为字面量 \"event=snake_case 键=值\"：" + calls.group(1) + "." + calls.group(2) + "("
                        + first + ")");
            }
        }
        return problems;
    }
}
