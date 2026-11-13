package com.liming.rag;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 文档自检（#196）：规则卡 {@code docs/rules/}、决策记录 {@code docs/decisions/} 和全仓库文档、注释里的引用必须有效。
 * 检查：相对链接和锚点存在；卡号唯一；引用的卡号和 D 编号存在；索引覆盖全部卡和决策；检查方式标签合法；
 * 决策每篇不超过 {@link #MAX_DECISION_LINES} 行；不再引用已拆分的 {@code DECISIONS.md}。已执行的 Flyway 迁移改注释会改变校验和，不扫描。
 * 违规样例在 {@code src/test/resources/doclinks/}，证明检查会指出问题。
 */
class DocLinksTest {

    private static final int MAX_DECISION_LINES = 40;
    private static final int MAX_AGENTS_LINES = 80;
    private static final int DECISION_COUNT = 71;

    private static final Set<String> SKIPPED_DIRS = Set.of(".git", "node_modules", "target", ".claude", "dist", ".idea");
    private static final Set<String> TEXT_SUFFIXES = Set.of(".md", ".java", ".ts", ".tsx", ".xml", ".yml", ".yaml",
            ".sql", ".sh", ".properties");

    private static final Pattern CARD_HEADING = Pattern.compile("(?m)^## ([A-Z]-\\d{2}) ");
    private static final Pattern CARD_REF = Pattern.compile("(?<![A-Za-z0-9-])([PABFT]-\\d{2})(?!\\d)");
    private static final Pattern DECISION_REF = Pattern.compile("(?<![A-Za-z0-9])D-(\\d{3})(?!\\d)");
    private static final Pattern DECISION_FILE = Pattern.compile("D-(\\d{3})-.+\\.md");
    private static final Pattern LINK = Pattern.compile("(?<!!)\\[[^\\]]*]\\(([^)\\s]+)\\)");
    private static final Pattern CHECK_LINE = Pattern.compile("(?m)^检查：(.*)$");
    private static final Pattern AUTOMATIC = Pattern.compile("自动\\[([^\\]]+)](（.*）)?");
    private static final Pattern STALE_REF = Pattern.compile("DECISIONS\\.md|AGENTS\\.md[“\"]");
    private static final List<String> CARD_FIELDS = List.of("规则：", "理由：", "状态：", "检查：");

    @Test
    @DisplayName("本仓库的文档、规则卡和决策记录没有问题")
    void repositoryDocsAreValid() throws IOException {
        Path root = Path.of("../../..").toRealPath();
        assertThat(problems(root, true)).isEmpty();
    }

    @Test
    @DisplayName("决策记录齐全，每篇不超过 40 行；AGENTS.md 不超过 80 行")
    void decisionsAndRoutingAreWithinLimits() throws IOException {
        Path root = Path.of("../../..").toRealPath();
        try (var files = Files.list(root.resolve("docs/decisions"))) {
            var decisions = files.filter(p -> DECISION_FILE.matcher(p.getFileName().toString()).matches()).toList();
            assertThat(decisions).hasSize(DECISION_COUNT);
            for (Path decision : decisions) {
                assertThat(Files.readAllLines(decision)).as(decision.getFileName().toString())
                        .hasSizeLessThanOrEqualTo(MAX_DECISION_LINES);
            }
        }
        assertThat(Files.readAllLines(root.resolve("AGENTS.md"))).hasSizeLessThanOrEqualTo(MAX_AGENTS_LINES);
    }

    @Test
    @DisplayName("正常的样例没有问题")
    void goodSampleHasNoProblems() throws IOException {
        assertThat(problems(sample("good"), false)).isEmpty();
    }

    @Test
    @DisplayName("断链和不存在的锚点会被指出")
    void detectsBrokenLinks() throws IOException {
        assertThat(problems(sample("broken-link"), false))
                .anyMatch(p -> p.contains("missing.md") && p.contains("不存在"))
                .anyMatch(p -> p.contains("#no-such-heading") && p.contains("锚点"));
    }

    @Test
    @DisplayName("重复卡号会被指出")
    void detectsDuplicateCard() throws IOException {
        assertThat(problems(sample("duplicate-card"), false)).anyMatch(p -> p.contains("P-01") && p.contains("重复"));
    }

    @Test
    @DisplayName("没有登记到索引的卡和决策会被指出")
    void detectsUnindexed() throws IOException {
        assertThat(problems(sample("unindexed"), false))
                .anyMatch(p -> p.contains("P-02") && p.contains("索引"))
                .anyMatch(p -> p.contains("D-002") && p.contains("索引"));
    }

    @Test
    @DisplayName("非法的检查方式会被指出")
    void detectsInvalidCheckTag() throws IOException {
        assertThat(problems(sample("bad-tag"), false))
                .anyMatch(p -> p.contains("P-01") && p.contains("检查方式"))
                .anyMatch(p -> p.contains("P-02") && p.contains("检查方式"));
    }

    @Test
    @DisplayName("引用不存在的卡号、决策编号和已拆分的 DECISIONS.md 会被指出")
    void detectsUnknownReferences() throws IOException {
        assertThat(problems(sample("unknown-ref"), false))
                .anyMatch(p -> p.contains("P-09") && p.contains("不存在"))
                .anyMatch(p -> p.contains("D-099") && p.contains("不存在"))
                .anyMatch(p -> p.contains("DECISIONS.md"));
    }

    private static Path sample(String name) throws IOException {
        return Path.of("src/test/resources/doclinks", name).toRealPath();
    }

    /** 返回 root 下全部问题；{@code realRepo} 为 true 时还检查“自动[测试名]”指向的测试文件存在。 */
    static List<String> problems(Path root, boolean realRepo) throws IOException {
        List<String> problems = new ArrayList<>();
        List<Path> files = textFiles(root);
        Map<String, Path> cards = checkCards(root, realRepo, files, problems);
        Set<String> decisions = checkDecisions(root, problems);
        checkIndexes(root, cards.keySet(), decisions, problems);
        for (Path file : files) {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            String name = root.relativize(file).toString();
            if (file.getFileName().toString().equals("DocLinksTest.java") || name.contains("/db/migration/")) {
                continue;
            }
            if (name.endsWith(".md")) {
                checkLinks(root, file, text, problems);
            }
            checkReferences(name, text, cards.keySet(), decisions, problems);
        }
        return problems;
    }

    private static Map<String, Path> checkCards(Path root, boolean realRepo, List<Path> files, List<String> problems)
            throws IOException {
        Map<String, Path> cards = new HashMap<>();
        for (Path file : files) {
            String rel = root.relativize(file).toString();
            if (!rel.startsWith("docs/rules/") || file.getFileName().toString().startsWith("00-")) {
                continue;
            }
            String text = Files.readString(file, StandardCharsets.UTF_8);
            Matcher heading = CARD_HEADING.matcher(text);
            List<int[]> spans = new ArrayList<>();
            List<String> ids = new ArrayList<>();
            while (heading.find()) {
                ids.add(heading.group(1));
                spans.add(new int[] {heading.start()});
            }
            for (int i = 0; i < ids.size(); i++) {
                int end = i + 1 < ids.size() ? spans.get(i + 1)[0] : text.length();
                String id = ids.get(i);
                if (cards.putIfAbsent(id, file) != null) {
                    problems.add(rel + "：卡号 " + id + " 重复");
                }
                checkCard(rel, id, text.substring(spans.get(i)[0], end), root, realRepo, files, problems);
            }
        }
        return cards;
    }

    private static void checkCard(String rel, String id, String body, Path root, boolean realRepo, List<Path> files,
            List<String> problems) {
        for (String field : CARD_FIELDS) {
            if (!body.contains("\n" + field)) {
                problems.add(rel + "：卡 " + id + " 缺少“" + field + "”");
            }
        }
        Matcher check = CHECK_LINE.matcher(body);
        if (!check.find()) {
            return;
        }
        String value = check.group(1).trim();
        Matcher automatic = AUTOMATIC.matcher(value);
        if (automatic.matches()) {
            if (realRepo) {
                for (String test : automatic.group(1).split("[、,，]")) {
                    if (files.stream().noneMatch(f -> f.getFileName().toString().startsWith(test.trim() + "."))
                            && !Files.exists(root.resolve("scripts/" + test.trim() + ".sh"))) {
                        problems.add(rel + "：卡 " + id + " 的检查 " + test + " 找不到对应的测试或脚本");
                    }
                }
            }
        } else if (!value.matches("(测试|评审)(（.*）)?")) {
            problems.add(rel + "：卡 " + id + " 的检查方式不合法：" + value + "（只能是 自动[测试名] / 测试 / 评审）");
        }
    }

    private static Set<String> checkDecisions(Path root, List<String> problems) throws IOException {
        Set<String> ids = new HashSet<>();
        Path dir = root.resolve("docs/decisions");
        if (!Files.isDirectory(dir)) {
            return ids;
        }
        try (var files = Files.list(dir)) {
            for (Path file : files.sorted().toList()) {
                Matcher m = DECISION_FILE.matcher(file.getFileName().toString());
                if (m.matches() && !ids.add("D-" + m.group(1))) {
                    problems.add(file.getFileName() + "：决策编号 D-" + m.group(1) + " 重复");
                }
            }
        }
        return ids;
    }

    private static void checkIndexes(Path root, Set<String> cards, Set<String> decisions, List<String> problems)
            throws IOException {
        Path ruleIndex = root.resolve("docs/rules/00-索引.md");
        if (Files.exists(ruleIndex)) {
            String text = Files.readString(ruleIndex, StandardCharsets.UTF_8);
            cards.stream().filter(id -> !text.contains("[" + id + "]")).sorted()
                    .forEach(id -> problems.add("docs/rules/00-索引.md：卡 " + id + " 没有登记到索引"));
        }
        Path decisionIndex = root.resolve("docs/decisions/README.md");
        if (Files.exists(decisionIndex)) {
            String text = Files.readString(decisionIndex, StandardCharsets.UTF_8);
            decisions.stream().filter(id -> !text.contains("[" + id + "]")).sorted()
                    .forEach(id -> problems.add("docs/decisions/README.md：决策 " + id + " 没有登记到索引"));
        }
    }

    private static void checkLinks(Path root, Path file, String text, List<String> problems) throws IOException {
        Matcher m = LINK.matcher(text);
        while (m.find()) {
            String target = m.group(1);
            if (target.matches("(?i)(https?|mailto):.*")) {
                continue;
            }
            int hash = target.indexOf('#');
            String path = hash >= 0 ? target.substring(0, hash) : target;
            String anchor = hash >= 0 ? target.substring(hash + 1) : "";
            Path resolved = path.isEmpty() ? file : file.getParent().resolve(path).normalize();
            String where = root.relativize(file) + "：链接 " + target;
            if (!Files.exists(resolved)) {
                problems.add(where + " 指向的文件不存在");
            } else if (!anchor.isEmpty() && resolved.toString().endsWith(".md")
                    && !anchors(Files.readString(resolved, StandardCharsets.UTF_8)).contains(anchor)) {
                problems.add(where + " 的锚点不存在");
            }
        }
    }

    private static Set<String> anchors(String markdown) {
        Set<String> anchors = new HashSet<>();
        for (String line : markdown.split("\n")) {
            if (line.startsWith("#")) {
                String title = line.replaceFirst("^#+\\s*", "").trim().toLowerCase(java.util.Locale.ROOT);
                StringBuilder slug = new StringBuilder();
                for (char c : title.toCharArray()) {
                    if (Character.isLetterOrDigit(c) || c == '-' || c == '_') {
                        slug.append(c);
                    } else if (c == ' ') {
                        slug.append('-');
                    }
                }
                anchors.add(slug.toString());
            }
        }
        return anchors;
    }

    private static void checkReferences(String name, String text, Set<String> cards, Set<String> decisions,
            List<String> problems) {
        boolean history = name.startsWith("docs/decisions/");
        if (!history && STALE_REF.matcher(text).find()) {
            problems.add(name + "：仍在引用已拆分的 DECISIONS.md 或 AGENTS.md 的章节名，请改成规则卡号或决策编号链接");
        }
        if (name.endsWith(".md") || name.endsWith(".java") || name.endsWith(".ts") || name.endsWith(".tsx")) {
            Matcher decision = DECISION_REF.matcher(text);
            while (decision.find()) {
                if (!decisions.contains("D-" + decision.group(1))) {
                    problems.add(name + "：引用的决策 D-" + decision.group(1) + " 不存在");
                }
            }
        }
        if (name.endsWith(".md") && !name.startsWith("docs/decisions/")) {
            Matcher card = CARD_REF.matcher(text);
            while (card.find()) {
                if (!cards.contains(card.group(1))) {
                    problems.add(name + "：引用的规则卡 " + card.group(1) + " 不存在");
                }
            }
        }
    }

    private static List<Path> textFiles(Path root) throws IOException {
        List<Path> files = new ArrayList<>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                String rel = root.relativize(dir).toString();
                boolean skipped = SKIPPED_DIRS.contains(dir.getFileName().toString())
                        || rel.endsWith("src/test/resources/doclinks");
                return skipped ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                String name = file.getFileName().toString();
                if (TEXT_SUFFIXES.stream().anyMatch(name::endsWith)) {
                    files.add(file);
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return files;
    }
}
