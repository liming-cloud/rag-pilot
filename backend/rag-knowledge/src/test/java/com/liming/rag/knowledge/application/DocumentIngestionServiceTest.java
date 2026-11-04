package com.liming.rag.knowledge.application;

import static com.liming.rag.knowledge.application.InMemoryKnowledge.TENANT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.liming.rag.knowledge.domain.Document;
import com.liming.rag.knowledge.domain.DocumentStatus;
import com.liming.rag.knowledge.domain.Validity;
import com.liming.rag.shared.UserId;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

/** 文档入库：校验、存原文件、记文档、发事件；新版本、删除、有效期（D-026、D-046）。 */
class DocumentIngestionServiceTest {

    private static final UserId ADMIN = new UserId(1);
    private static final UserId OUTSIDER = new UserId(3);
    private static final byte[] TEXT = "收银员交班时要清点备用金。".getBytes(StandardCharsets.UTF_8);

    private final InMemoryKnowledge fixture = new InMemoryKnowledge();
    private final List<Object> events = new ArrayList<>();
    private final KnowledgeBaseService knowledgeBases = new KnowledgeBaseService(fixture.knowledgeBases,
            fixture.queries, fixture.users, fixture.calendar, query -> List.of());
    private final DocumentIngestionService service = new DocumentIngestionService(knowledgeBases,
            fixture.documents, fixture.files, events::add, new DocumentRemover(fixture.documents, fixture.files));
    private final long kb;

    {
        fixture.users.user(1, "管理员").user(3, "外人");
        kb = knowledgeBases.create(TENANT, ADMIN, "门店规范").id();
    }

    @Test
    @DisplayName("上传：存原文件，记一份“解析中”的文档，事务提交后由后台处理（发 DocumentReceived）")
    void storesFileAndQueuesParsing() {
        var document = service.ingest(TENANT, ADMIN, kb, "收银规范.txt", TEXT, null, null, null);

        assertThat(document.status()).isEqualTo(DocumentStatus.PARSING);
        assertThat(document.mediaType()).isEqualTo("text/plain");
        assertThat(document.sizeBytes()).isEqualTo(TEXT.length);
        assertThat(document.validity()).isEqualTo(Validity.ALWAYS);
        assertThat(fixture.files.has(TENANT, document.sha256())).isTrue();
        assertThat(events).containsExactly(new DocumentReceived(TENANT, document.id()));
    }

    @Test
    @DisplayName("格式不支持、空文件、超过 50 MB、文件名超过 255 个字符都直接拒绝，不存文件、不记文档")
    void rejectsBadFiles() {
        assertReason(() -> service.ingest(TENANT, ADMIN, kb, "旧版.doc", TEXT, null, null, null),
                KnowledgeException.Reason.UNSUPPORTED_TYPE);
        assertReason(() -> service.ingest(TENANT, ADMIN, kb, "没有扩展名", TEXT, null, null, null),
                KnowledgeException.Reason.UNSUPPORTED_TYPE);
        assertReason(() -> service.ingest(TENANT, ADMIN, kb, "空.txt", new byte[0], null, null, null),
                KnowledgeException.Reason.INVALID);
        assertReason(() -> service.ingest(TENANT, ADMIN, kb, "大.txt",
                new byte[(int) DocumentIngestionService.MAX_SIZE_BYTES + 1], null, null, null),
                KnowledgeException.Reason.INVALID);
        assertReason(() -> service.ingest(TENANT, ADMIN, kb, "长".repeat(252) + ".txt", TEXT, null, null, null),
                KnowledgeException.Reason.INVALID);

        assertThat(fixture.documents.rows).isEmpty();
        assertThat(fixture.files.stored).isEmpty();
        assertThat(events).isEmpty();
    }

    @Test
    @DisplayName("扩展名不区分大小写：.PDF、.Docx、.XLS、.markdown 都能识别")
    void recognizesExtensionsCaseInsensitively() {
        assertThat(service.ingest(TENANT, ADMIN, kb, "a.PDF", bytes("a"), null, null, null).mediaType())
                .isEqualTo("application/pdf");
        assertThat(service.ingest(TENANT, ADMIN, kb, "b.Docx", bytes("b"), null, null, null).mediaType())
                .isEqualTo("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        assertThat(service.ingest(TENANT, ADMIN, kb, "c.XLS", bytes("c"), null, null, null).mediaType())
                .isEqualTo("application/vnd.ms-excel");
        assertThat(service.ingest(TENANT, ADMIN, kb, "d.markdown", bytes("d"), null, null, null).mediaType())
                .isEqualTo("text/markdown");
    }

    @Test
    @DisplayName("同一个库里重复上传同一份文件是 CONFLICT；并发撞上唯一约束也是 CONFLICT")
    void rejectsDuplicates() {
        service.ingest(TENANT, ADMIN, kb, "收银规范.txt", TEXT, null, null, null);

        assertReason(() -> service.ingest(TENANT, ADMIN, kb, "改了名字.txt", TEXT, null, null, null),
                KnowledgeException.Reason.CONFLICT);
        fixture.documents.failNextInsert = new DuplicateKeyException("document_content_unique");
        assertReason(() -> service.ingest(TENANT, ADMIN, kb, "并发.txt", bytes("并发"), null, null, null),
                KnowledgeException.Reason.CONFLICT);
        assertThat(fixture.documents.rows).hasSize(1);
    }

    @Test
    @DisplayName("不能管理这个库的人上传、替换、改有效期、删除都按不存在处理")
    void requiresManageableKnowledgeBase() {
        var document = service.ingest(TENANT, ADMIN, kb, "收银规范.txt", TEXT, null, null, null);

        assertReason(() -> service.ingest(TENANT, OUTSIDER, kb, "外人.txt", bytes("外人"), null, null, null),
                KnowledgeException.Reason.NOT_FOUND);
        assertReason(() -> service.changeValidity(TENANT, OUTSIDER, kb, document.id(), "", ""),
                KnowledgeException.Reason.NOT_FOUND);
        assertReason(() -> service.delete(TENANT, OUTSIDER, kb, document.id()), KnowledgeException.Reason.NOT_FOUND);
        assertThat(fixture.documents.rows).containsOnlyKeys(document.id());
    }

    @Test
    @DisplayName("有效期：yyyy-MM-dd；不传时长期有效；格式不对、失效早于生效都是 INVALID")
    void parsesValidity() {
        var document = service.ingest(TENANT, ADMIN, kb, "a.txt", bytes("a"), null, "2026-11-01", " 2026-12-31 ");
        assertThat(document.validity()).isEqualTo(new Validity(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 12, 31)));

        assertReason(() -> service.ingest(TENANT, ADMIN, kb, "b.txt", bytes("b"), null, "2026/11/01", null),
                KnowledgeException.Reason.INVALID);
        assertReason(() -> service.ingest(TENANT, ADMIN, kb, "c.txt", bytes("c"), null, "2026-12-01", "2026-11-01"),
                KnowledgeException.Reason.INVALID);
    }

    @Test
    @DisplayName("新版本：要替换的文档必须在这个库里；不传失效日期时继承旧版本的，传空串表示不限")
    void replacesWithinKnowledgeBase() {
        var old = fixture.documents.ready(kb, "陈列规范.txt", "old-sha", new Validity(null, LocalDate.of(2026, 12, 31)));
        var otherKb = knowledgeBases.create(TENANT, ADMIN, "商品知识").id();

        var inherited = service.ingest(TENANT, ADMIN, kb, "陈列规范-新版.txt", bytes("新版"), old.id(), null, null);
        var cleared = service.ingest(TENANT, ADMIN, kb, "陈列规范-再版.txt", bytes("再版"), old.id(), null, "");

        assertThat(inherited.replacesDocumentId()).isEqualTo(old.id());
        assertThat(inherited.validity().expiresOn()).isEqualTo(LocalDate.of(2026, 12, 31));
        assertThat(cleared.validity().expiresOn()).isNull();
        assertReason(() -> service.ingest(TENANT, ADMIN, otherKb, "错库.txt", bytes("错库"), old.id(), null, null),
                KnowledgeException.Reason.NOT_FOUND);
    }

    @Test
    @DisplayName("删除：文档和分块一起删；租户里没有别的文档用到这份原始文件时才删文件")
    void deletesDocumentAndUnreferencedFile() {
        var shared = service.ingest(TENANT, ADMIN, kb, "共用.txt", TEXT, null, null, null);
        var otherKb = knowledgeBases.create(TENANT, ADMIN, "商品知识").id();
        var copy = service.ingest(TENANT, ADMIN, otherKb, "共用.txt", TEXT, null, null, null);

        service.delete(TENANT, ADMIN, kb, shared.id());
        assertThat(fixture.files.has(TENANT, shared.sha256())).as("另一个库还在用").isTrue();
        service.delete(TENANT, ADMIN, otherKb, copy.id());
        assertThat(fixture.files.has(TENANT, shared.sha256())).isFalse();

        assertThat(fixture.documents.rows).isEmpty();
        assertReason(() -> service.delete(TENANT, ADMIN, kb, shared.id()), KnowledgeException.Reason.NOT_FOUND);
    }

    @Test
    @DisplayName("文档不在这个库里时删除、改有效期都按不存在处理")
    void requiresDocumentInKnowledgeBase() {
        var otherKb = knowledgeBases.create(TENANT, ADMIN, "商品知识").id();
        var document = service.ingest(TENANT, ADMIN, otherKb, "a.txt", TEXT, null, null, null);

        assertReason(() -> service.delete(TENANT, ADMIN, kb, document.id()), KnowledgeException.Reason.NOT_FOUND);
        assertReason(() -> service.changeValidity(TENANT, ADMIN, kb, document.id(), "", ""),
                KnowledgeException.Reason.NOT_FOUND);
        assertThat(fixture.documents.rows).containsOnlyKeys(document.id());
    }

    @Test
    @DisplayName("改有效期：保存后按新日期判断；null 和空串都表示不限")
    void changesValidity() {
        Document document = fixture.documents.ready(kb, "a.txt", "sha-a", Validity.ALWAYS);

        var changed = service.changeValidity(TENANT, ADMIN, kb, document.id(), "2026-12-01", null);

        assertThat(changed.validity()).isEqualTo(new Validity(LocalDate.of(2026, 12, 1), null));
        assertThat(fixture.calendar.state(changed.validity())).isEqualTo(Validity.State.PENDING);
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static void assertReason(ThrowingCallable call, KnowledgeException.Reason reason) {
        assertThatThrownBy(call).isInstanceOfSatisfying(KnowledgeException.class,
                e -> assertThat(e.reason()).isEqualTo(reason));
    }
}
