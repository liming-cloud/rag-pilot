package com.liming.rag.knowledge.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.liming.rag.knowledge.domain.Document;
import com.liming.rag.knowledge.domain.DocumentStatus;
import com.liming.rag.knowledge.domain.Validity;
import com.liming.rag.shared.TenantId;
import java.lang.reflect.Proxy;
import java.time.OffsetDateTime;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 文档的乐观锁（#197）：更新影响 0 行说明文档已被别的操作改过或删除，抛冲突异常，不静默丢掉。 */
class MybatisDocumentRepositoryTest {

    private int affectedRows;

    private final DocumentMapper mapper = (DocumentMapper) Proxy.newProxyInstance(
            DocumentMapper.class.getClassLoader(), new Class<?>[] {DocumentMapper.class}, (proxy, method, args) -> {
                if (method.getName().equals("update")) {
                    return affectedRows;
                }
                throw new UnsupportedOperationException(method.getName());
            });

    private final MybatisDocumentRepository repository = new MybatisDocumentRepository(mapper, null);

    @BeforeAll
    static void tableInfo() {
        // Lambda 条件按实体的表结构找列名，单元测试里没有 MyBatis 启动，手工登记一次。
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), DocumentDO.class);
    }

    @Test
    @DisplayName("整份保存时版本号对不上（影响 0 行）抛冲突异常，内存里的版本号不变")
    void saveConflictsWhenVersionChanged() {
        var document = document(DocumentStatus.FAILED);
        affectedRows = 0;

        assertThatThrownBy(() -> repository.save(document)).isInstanceOf(Document.ConcurrentChangeException.class);
        assertThat(document.version()).isEqualTo(3);
    }

    @Test
    @DisplayName("写处理结果时文档已被删除或不在解析中（影响 0 行）抛冲突异常")
    void saveOutcomeConflictsWhenNoLongerParsing() {
        var document = document(DocumentStatus.PARSING);
        document.markReady(2);
        affectedRows = 0;

        assertThatThrownBy(() -> repository.saveOutcome(document))
                .isInstanceOf(Document.ConcurrentChangeException.class);
    }

    @Test
    @DisplayName("写入成功后版本号加一，下次保存以新版本号为准")
    void advancesVersionAfterSave() {
        var document = document(DocumentStatus.PARSING);
        affectedRows = 1;

        repository.saveOutcome(document);
        repository.save(document);

        assertThat(document.version()).isEqualTo(5);
    }

    private static Document document(DocumentStatus status) {
        return Document.restore(7, 3, new TenantId(1), 2, "收银规范.txt", "text/plain", "sha", 10, status, null, 0,
                OffsetDateTime.parse("2026-11-10T09:00:00+08:00"), null, new Validity(null, null), Document.Retry.FIRST);
    }
}
