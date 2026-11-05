package com.liming.rag;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.FileSystemResource;

/**
 * 通用内核 + 行业包（D-045）：后端各模块的 Java 主代码（含注释）里不出现任何行业包的行业标志词和词表里的词，
 * 行业内容只能写在 {@code rag-settings/src/main/resources/industry-packs/} 里。测试、评测集、行业包本身不扫。
 * 前端由 {@code frontend/src/app/structure.test.ts} 用同一份行业包检查。
 */
class KernelNeutralityTest {

    private static final String PACKS = "src/main/resources/industry-packs";

    /** 所有行业包的行业标志词、词表的上位词和下位词。 */
    static Set<String> industryWords() throws IOException {
        var words = new TreeSet<String>();
        try (Stream<Path> packs = Files.list(BackendTree.module("rag-settings").resolve(PACKS))) {
            for (var pack : packs.map(dir -> dir.resolve("pack.yml")).filter(Files::exists).toList()) {
                var yaml = new YamlPropertiesFactoryBean();
                yaml.setResources(new FileSystemResource(pack));
                var properties = yaml.getObject();
                properties.stringPropertyNames().stream()
                        .filter(key -> key.matches("markers\\[\\d+]|vocabulary\\[\\d+]\\.(term|members\\[\\d+])"))
                        .map(properties::getProperty)
                        .forEach(words::add);
            }
        }
        return words;
    }

    /** 一个文件里出现的行业词；{@code file} 是相对 backend 的路径。 */
    static List<String> problems(String file, String source, Set<String> words) {
        return words.stream().filter(source::contains)
                .map(word -> file + " 出现了行业词“" + word + "”：行业内容放进行业包，内核用中性说法").toList();
    }

    @Test
    @DisplayName("后端 Java 主代码里没有行业词")
    void kernelHasNoIndustryWords() throws IOException {
        Path backend = BackendTree.root();
        var words = industryWords();
        assertThat(words).as("应读到新零售行业包的词").contains("门店", "鲜食", "便当");

        List<Path> sources;
        try (Stream<Path> walk = Files.walk(backend)) {
            sources = walk.filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> BackendTree.moduleRelative(backend, path)
                            .matches("rag-[a-z]+/src/main/java/.+"))
                    .toList();
        }
        assertThat(sources).as("应找到各模块的主代码").hasSizeGreaterThan(50);
        var found = new ArrayList<String>();
        for (var path : sources) {
            found.addAll(problems(BackendTree.moduleRelative(backend, path), Files.readString(path), words));
        }
        assertThat(found).isEmpty();
    }

    @Test
    @DisplayName("写了行业词会被指出，中性说法不会")
    void detectsIndustryWords() {
        var words = Set.of("门店", "便当");
        assertThat(problems("rag-answering/src/main/java/A.java", "/** 回答门店员工的问题。 */", words)).hasSize(1);
        assertThat(problems("rag-answering/src/main/java/A.java", "String s = \"便当几点下架\";", words)).hasSize(1);
        assertThat(problems("rag-answering/src/main/java/A.java", "/** 回答员工的问题。 */", words)).isEmpty();
    }
}
