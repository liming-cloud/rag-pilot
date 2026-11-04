package com.liming.rag;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 后端源码目录（#145）：模块分在 {@code platform}、{@code contexts}、{@code boot} 三层下。按目录扫描的规则测试用它找后端根目录和模块，
 * 并把文件路径换成去掉层名的“{@code rag-<名>/src/...}”，规则本身不随分层变化。
 */
final class BackendTree {

    static final List<String> LAYERS = List.of("platform", "contexts", "boot");

    private BackendTree() {
    }

    /** 后端根目录（{@code backend/}）；测试的工作目录是 {@code backend/boot/rag-app}。 */
    static Path root() throws IOException {
        return Path.of("../..").toRealPath();
    }

    /** 某个模块的目录，如 {@code rag-settings}。 */
    static Path module(String name) throws IOException {
        var root = root();
        return LAYERS.stream().map(layer -> root.resolve(layer).resolve(name)).filter(Files::isDirectory).findFirst()
                .orElseThrow(() -> new IllegalStateException("找不到模块 " + name));
    }

    /** 相对后端根目录、去掉层名的路径，如 {@code rag-knowledge/src/main/java/...}。 */
    static String moduleRelative(Path root, Path file) {
        var relative = root.relativize(file).toString().replace('\\', '/');
        int slash = relative.indexOf('/');
        return slash > 0 && LAYERS.contains(relative.substring(0, slash)) ? relative.substring(slash + 1) : relative;
    }
}
