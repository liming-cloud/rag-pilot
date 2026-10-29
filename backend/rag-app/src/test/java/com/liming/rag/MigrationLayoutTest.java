package com.liming.rag;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Flyway 迁移放在所属模块的 {@code db/migration/<模块名>/}，文件名为 {@code V<yyyyMMddNNNN>__说明.sql}，版本号全局不重复。
 */
class MigrationLayoutTest {

    private static final Pattern LAYOUT = Pattern.compile(
            "rag-([a-z]+)/src/main/resources/db/migration/([a-z]+)/V(\\d{12})__[^/]+\\.sql");

    /** 不符合约定时返回原因；符合时返回空。 */
    static Optional<String> problem(String relativePath) {
        Matcher m = LAYOUT.matcher(relativePath);
        if (!m.matches()) {
            return Optional.of("路径或文件名不符合 rag-<模块>/src/main/resources/db/migration/<模块>/V<yyyyMMddNNNN>__说明.sql");
        }
        if (!m.group(1).equals(m.group(2))) {
            return Optional.of("迁移目录 " + m.group(2) + " 与所属模块 rag-" + m.group(1) + " 不一致");
        }
        return Optional.empty();
    }

    @Test
    @DisplayName("全部迁移文件位置、命名符合约定，版本号不重复")
    void migrationsFollowLayout() throws IOException {
        Path backend = Path.of("..").toRealPath();
        List<String> files;
        try (Stream<Path> walk = Files.walk(backend)) {
            files = walk.filter(p -> p.toString().endsWith(".sql"))
                    .map(p -> backend.relativize(p).toString().replace('\\', '/'))
                    .filter(p -> p.contains("/src/main/resources/db/migration/"))
                    .sorted()
                    .toList();
        }
        assertThat(files).as("应找到各模块的迁移文件").hasSizeGreaterThan(5);

        List<String> problems = new ArrayList<>();
        Map<String, String> versions = new HashMap<>();
        for (String file : files) {
            problem(file).ifPresent(reason -> problems.add(file + "：" + reason));
            Matcher m = LAYOUT.matcher(file);
            if (m.matches()) {
                String previous = versions.put(m.group(3), file);
                if (previous != null) {
                    problems.add(file + "：版本号与 " + previous + " 重复");
                }
            }
        }
        assertThat(problems).isEmpty();
    }

    @Test
    @DisplayName("违规的迁移路径会被指出")
    void detectsViolations() {
        assertThat(problem("rag-knowledge/src/main/resources/db/migration/knowledge/V202610260101__create.sql"))
                .isEmpty();
        assertThat(problem("rag-knowledge/src/main/resources/db/migration/V202610260101__create.sql"))
                .as("没有模块子目录").isPresent();
        assertThat(problem("rag-knowledge/src/main/resources/db/migration/identity/V202610260101__create.sql"))
                .as("放进了别的模块的目录").isPresent();
        assertThat(problem("rag-knowledge/src/main/resources/db/migration/knowledge/V1__create.sql"))
                .as("版本号不是 yyyyMMddNNNN").isPresent();
    }
}
