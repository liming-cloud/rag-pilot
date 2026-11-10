package com.liming.rag;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;

/**
 * 复杂度门禁（#199，D-067）：{@code backend/checkstyle.xml} 的两条规则拦住超上限的方法、放行正好在上限的方法。
 * 报告由本模块的 {@code checkstyle-fixture} 执行在测试前对 {@code archfixture/complexity} 生成，
 * 所以要经 Maven 跑（{@code ./mvnw verify}，或 {@code ./mvnw -pl backend/boot/rag-app test -Dtest=ComplexityGateTest}）。
 */
class ComplexityGateTest {

    private static final Path REPORT = Path.of("target/checkstyle-fixture.xml");

    @Test
    @DisplayName("圈复杂度 11、语句 31 条的方法被拦下，10 和 30 的放行")
    void blocksMethodsOverLimits() throws Exception {
        assertThat(REPORT).as("先经 Maven 跑到 process-test-classes 生成样例报告").exists();
        var violations = violations();

        assertThat(violations.get("TooComplex.java")).containsExactlyInAnyOrder(
                "metrics.CyclomaticComplexityCheck", "sizes.ExecutableStatementCountCheck");
        // Checkstyle 的缓存只记通过的文件，再次运行时合规样例不出现在报告里，同样算放行。
        assertThat(violations.getOrDefault("WithinLimits.java", List.of())).isEmpty();
    }

    /** 每个样例文件触发的规则（去掉 Checkstyle 的包名前缀）。 */
    private static Map<String, List<String>> violations() throws Exception {
        var document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(REPORT.toFile());
        var result = new HashMap<String, List<String>>();
        var files = document.getElementsByTagName("file");
        for (int i = 0; i < files.getLength(); i++) {
            var file = (Element) files.item(i);
            var rules = new ArrayList<String>();
            var errors = file.getElementsByTagName("error");
            for (int j = 0; j < errors.getLength(); j++) {
                rules.add(((Element) errors.item(j)).getAttribute("source")
                        .replace("com.puppycrawl.tools.checkstyle.checks.", ""));
            }
            result.put(Path.of(file.getAttribute("name")).getFileName().toString(), rules);
        }
        return result;
    }
}
