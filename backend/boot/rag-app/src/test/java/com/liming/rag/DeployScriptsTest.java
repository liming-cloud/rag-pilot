package com.liming.rag;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 部署脚本（#134）：macOS 自带的 bash 3.2 在 UTF-8 下会把紧跟在 {@code $变量} 后面的中文字符当成变量名的一部分，
 * {@code set -u} 时直接报 unbound variable（在本机 Docker 上演练时 build.sh 就这样失败）。变量后面紧跟非 ASCII 字符时要写成
 * {@code ${变量}}。
 */
class DeployScriptsTest {

    private static final Pattern BARE_VARIABLE_BEFORE_NON_ASCII = Pattern.compile("\\$[A-Za-z_][A-Za-z0-9_]*[^\\x00-\\x7F]");

    @Test
    @DisplayName("部署脚本里紧跟中文的变量都带花括号")
    void variablesBeforeNonAsciiAreBraced() throws IOException {
        var violations = new ArrayList<String>();
        try (Stream<Path> scripts = Files.list(Path.of("../../../deploy"))) {
            for (Path script : scripts.filter(path -> path.toString().endsWith(".sh")).sorted().toList()) {
                List<String> lines = Files.readAllLines(script, StandardCharsets.UTF_8);
                for (int i = 0; i < lines.size(); i++) {
                    if (BARE_VARIABLE_BEFORE_NON_ASCII.matcher(lines.get(i)).find()) {
                        violations.add(script.getFileName() + ":" + (i + 1) + "  " + lines.get(i).strip());
                    }
                }
            }
        }
        assertThat(violations).as("改成 ${变量}").isEmpty();
    }
}
