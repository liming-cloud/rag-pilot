package com.liming.rag;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Mapper XML 放在所属模块的 {@code mapper/<模块名>/<Mapper 名>.xml}，namespace 指向同模块 infrastructure 包里的同名接口。
 */
class MapperXmlLayoutTest {

    private static final Pattern LAYOUT = Pattern.compile(
            "rag-([a-z]+)/src/main/resources/mapper/([a-z]+)/([A-Z][A-Za-z]*Mapper)\\.xml");
    private static final Pattern NAMESPACE = Pattern.compile("<mapper\\s+namespace=\"([^\"]+)\"");

    /** 不符合约定时返回原因；符合时返回空。 */
    static Optional<String> problem(String relativePath, String content) {
        Matcher m = LAYOUT.matcher(relativePath);
        if (!m.matches()) {
            return Optional.of("路径不符合 rag-<模块>/src/main/resources/mapper/<模块>/<Xxx>Mapper.xml");
        }
        if (!m.group(1).equals(m.group(2))) {
            return Optional.of("目录 mapper/" + m.group(2) + " 与所属模块 rag-" + m.group(1) + " 不一致");
        }
        Matcher ns = NAMESPACE.matcher(content);
        String expectedPrefix = "com.liming.rag." + m.group(1) + ".infrastructure.";
        if (!ns.find() || !ns.group(1).startsWith(expectedPrefix) || !ns.group(1).endsWith("." + m.group(3))) {
            return Optional.of("namespace 应为 " + expectedPrefix + "…." + m.group(3));
        }
        return Optional.empty();
    }

    @Test
    @DisplayName("全部 Mapper XML 位置、命名和 namespace 符合约定")
    void mapperXmlFollowsLayout() throws IOException {
        Path backend = Path.of("..").toRealPath();
        List<Path> files;
        try (Stream<Path> walk = Files.walk(backend)) {
            files = walk.filter(p -> p.toString().endsWith(".xml"))
                    .filter(p -> p.toString().contains("/src/main/resources/"))
                    .sorted()
                    .toList();
        }
        List<String> problems = new ArrayList<>();
        int mappers = 0;
        for (Path file : files) {
            String content = Files.readString(file);
            if (!content.contains("<mapper")) {
                continue;
            }
            mappers++;
            String relative = backend.relativize(file).toString().replace('\\', '/');
            problem(relative, content).ifPresent(reason -> problems.add(relative + "：" + reason));
        }
        assertThat(mappers).as("应找到 Mapper XML").isGreaterThan(0);
        assertThat(problems).isEmpty();
    }

    @Test
    @DisplayName("违规的 Mapper XML 会被指出")
    void detectsViolations() {
        String ok = "<mapper namespace=\"com.liming.rag.knowledge.infrastructure.persistence.ChunkSearchMapper\">";
        assertThat(problem("rag-knowledge/src/main/resources/mapper/knowledge/ChunkSearchMapper.xml", ok)).isEmpty();
        assertThat(problem("rag-knowledge/src/main/resources/mapper/ChunkSearchMapper.xml", ok))
                .as("没有模块子目录").isPresent();
        assertThat(problem("rag-knowledge/src/main/resources/mapper/answering/ChunkSearchMapper.xml", ok))
                .as("放进了别的模块的目录").isPresent();
        assertThat(problem("rag-knowledge/src/main/resources/mapper/knowledge/ChunkMapper.xml", ok))
                .as("文件名与接口名不一致").isPresent();
    }
}
