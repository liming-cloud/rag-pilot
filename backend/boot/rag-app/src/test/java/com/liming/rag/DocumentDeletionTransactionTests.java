package com.liming.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.liming.rag.knowledge.application.DocumentIngestionService;
import com.liming.rag.knowledge.application.DocumentIngestionService.Upload;
import com.liming.rag.knowledge.application.FileStorage;
import com.liming.rag.knowledge.application.KnowledgeBaseService;
import com.liming.rag.shared.TenantId;
import com.liming.rag.shared.UserId;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** 删除文档时的原始文件（#145 C5）：事务提交后才删文件；事务回滚时文件和记录都还在，不会留下指向已删文件的记录。 */
@SpringBootTest(properties = "rag.knowledge.storage.root=${java.io.tmpdir}/rag-deletion-test-files")
@Testcontainers
class DocumentDeletionTransactionTests {

    private static final TenantId TENANT = new TenantId(1);

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = TestDatabase.container();

    @Autowired
    private KnowledgeBaseService knowledgeBases;

    @Autowired
    private DocumentIngestionService ingestion;

    @Autowired
    private FileStorage storage;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("删除所在的事务回滚时原始文件和记录都还在；提交后才删掉文件")
    void deletesFileOnlyAfterCommit() {
        var admin = new UserId(jdbc.queryForObject("select id from app_user where username = ?", Long.class,
                TestLogin.ADMIN_USERNAME));
        long kb = knowledgeBases.create(TENANT, admin, "删除事务测试库").id();
        var content = "冷柜每周二除霜一次。".getBytes(StandardCharsets.UTF_8);
        long documentId = ingestion.ingest(TENANT, admin, Upload.of(kb, "除霜.txt", content)).id();
        var sha256 = jdbc.queryForObject("select sha256 from document where id = ?", String.class, documentId);

        transactions.executeWithoutResult(status -> {
            ingestion.delete(TENANT, admin, kb, documentId);
            status.setRollbackOnly();
        });
        assertThat(jdbc.queryForObject("select count(*) from document where id = ?", Integer.class, documentId))
                .as("回滚后记录还在").isEqualTo(1);
        assertThat(storage.read(TENANT, sha256)).as("回滚后原始文件还在").isEqualTo(content);

        ingestion.delete(TENANT, admin, kb, documentId);
        assertThat(jdbc.queryForObject("select count(*) from document where id = ?", Integer.class, documentId))
                .isZero();
        assertThatThrownBy(() -> storage.read(TENANT, sha256)).as("提交后原始文件删掉了")
                .isInstanceOf(UncheckedIOException.class);
    }
}
