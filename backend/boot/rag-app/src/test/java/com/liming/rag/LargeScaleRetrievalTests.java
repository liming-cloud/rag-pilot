package com.liming.rag;

import static org.assertj.core.api.Assertions.assertThat;

import com.liming.rag.knowledge.application.ChunkIndex;
import com.liming.rag.knowledge.application.Grantee;
import com.liming.rag.shared.TenantId;
import com.liming.rag.shared.UserId;
import java.time.LocalDate;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 资料多了以后的向量召回（#145）。pgvector 的 HNSW 索引先按距离扫出一批（默认 {@code hnsw.ef_search = 40}）再按 WHERE 过滤，
 * 员工能访问的资料只占一小部分时，过滤后剩下的远少于要的条数，甚至一条不剩（pgvector 文档“Filtering”）。
 * 这里用随机向量直接写库，不经解析和向量化，只看召回的条数和范围。
 *
 * <p>数据库按代价决定走不走 HNSW 索引：数据量、统计信息、参数是字面量还是绑定参数都会影响，测试规模下不一定会选它。
 * 所以测试里关掉排序（{@code enable_sort = off}），召回只能按索引顺序取，固定在“选了 HNSW 索引”的情形（资料多了以后的常态）。
 */
@SpringBootTest(properties = "rag.knowledge.storage.root=${java.io.tmpdir}/rag-large-retrieval-test-files")
@Testcontainers
class LargeScaleRetrievalTests {

    /** 分块总数，每 50 块里有 1 块在员工能访问的库里（2%）。 */
    private static final int CHUNKS = 5_000;
    private static final int LIMIT = 30;
    private static final long TENANT = 1;
    private static final long STAFF = 424_242;

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = TestDatabase.container();

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ChunkIndex index;

    @Test
    @DisplayName("员工只能访问 2% 的资料时，向量召回仍返回要的条数，且都来自可访问的库")
    void nearestKeepsRecallWhenFilteredToFewChunks() {
        long granted = knowledgeBase("大规模召回-可访问");
        long other = knowledgeBase("大规模召回-不可访问");
        long grantedDocument = document(granted, "a".repeat(64));
        long otherDocument = document(other, "b".repeat(64));
        // 向量在每行里单独生成（WHERE g > 0 让子查询依赖当前行，不被提成常量）。
        jdbc.update("""
                INSERT INTO chunk (tenant_id, knowledge_base_id, document_id, ordinal, heading_path, content, token_count,
                                   embedding)
                SELECT ?, CASE WHEN g % 50 = 0 THEN ? ELSE ? END, CASE WHEN g % 50 = 0 THEN ? ELSE ? END, g, '',
                       'chunk-' || g, 1,
                       (SELECT array_agg(random())::vector(1024) FROM generate_series(1, 1024) WHERE g > 0)
                FROM generate_series(1, ?) g
                """, TENANT, granted, other, grantedDocument, otherDocument, CHUNKS);
        jdbc.update("INSERT INTO knowledge_base_member (tenant_id, knowledge_base_id, user_id) VALUES (?, ?, ?)",
                TENANT, granted, STAFF);
        jdbc.execute("ANALYZE chunk");
        forceIndexOrder();

        var ids = index.nearest(new Grantee(new TenantId(TENANT), new UserId(STAFF), List.of()), List.of(),
                LocalDate.now(), randomVector(), LIMIT);

        assertThat(ids).as("可访问的分块有 %d 块，应召回 %d 条", CHUNKS / 50, LIMIT).hasSize(LIMIT);
        assertThat(jdbc.queryForList("SELECT DISTINCT knowledge_base_id FROM chunk WHERE id = ANY (?)", Long.class,
                (Object) ids.toArray(Long[]::new))).containsExactly(granted);
    }

    /** 测试库的账号关掉排序，连接池换新连接后生效：向量召回只能按 HNSW 索引顺序取。 */
    private void forceIndexOrder() {
        jdbc.execute("ALTER ROLE CURRENT_USER SET enable_sort = off");
        ((com.zaxxer.hikari.HikariDataSource) jdbc.getDataSource()).getHikariPoolMXBean().softEvictConnections();
    }

    private long knowledgeBase(String name) {
        return jdbc.queryForObject("INSERT INTO knowledge_base (tenant_id, name) VALUES (?, ?) RETURNING id", Long.class,
                TENANT, name);
    }

    private long document(long knowledgeBaseId, String sha256) {
        return jdbc.queryForObject("""
                INSERT INTO document (tenant_id, knowledge_base_id, filename, media_type, sha256, size_bytes, status)
                VALUES (?, ?, 'large.txt', 'text/plain', ?, 1, 'READY') RETURNING id
                """, Long.class, TENANT, knowledgeBaseId, sha256);
    }

    private static float[] randomVector() {
        var random = new Random(7);
        var vector = new float[1024];
        for (int i = 0; i < vector.length; i++) {
            vector[i] = random.nextFloat();
        }
        return vector;
    }
}
