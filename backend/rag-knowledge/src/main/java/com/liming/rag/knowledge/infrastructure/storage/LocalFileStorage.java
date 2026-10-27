package com.liming.rag.knowledge.infrastructure.storage;

import com.liming.rag.common.TenantId;
import com.liming.rag.knowledge.application.FileStorage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 本地卷文件存储：{@code <根目录>/<租户>/<摘要前两位>/<摘要>}。
 *
 * <p>先写临时文件再原子改名，避免进程中断留下半个文件。路径只由租户编号和十六进制摘要组成，
 * 不使用用户上传的文件名，杜绝路径穿越。
 */
@Component
class LocalFileStorage implements FileStorage {

    private final Path root;

    LocalFileStorage(@Value("${rag.storage.root:./data/files}") String root) {
        this.root = Path.of(root).toAbsolutePath().normalize();
    }

    @Override
    public void store(TenantId tenantId, String sha256, byte[] content) {
        var target = pathOf(tenantId, sha256);
        if (Files.exists(target)) {
            return;
        }
        try {
            Files.createDirectories(target.getParent());
            var temporary = Files.createTempFile(target.getParent(), sha256, ".part");
            Files.write(temporary, content);
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("保存上传文件失败", e);
        }
    }

    @Override
    public byte[] read(TenantId tenantId, String sha256) {
        try {
            return Files.readAllBytes(pathOf(tenantId, sha256));
        } catch (IOException e) {
            throw new UncheckedIOException("读取上传文件失败", e);
        }
    }

    private Path pathOf(TenantId tenantId, String sha256) {
        if (!sha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("文件摘要格式不正确");
        }
        return root.resolve(Long.toString(tenantId.value())).resolve(sha256.substring(0, 2)).resolve(sha256);
    }
}
