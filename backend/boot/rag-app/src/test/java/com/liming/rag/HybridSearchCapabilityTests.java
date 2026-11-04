package com.liming.rag;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
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
 * 验证数据库镜像同时具备混合检索需要的两种能力（Issue #3）：
 * jieba 中文分词的 BM25 关键词检索，以及 pgvector 向量距离排序。
 *
 * <p>只用临时表验证能力本身，不涉及业务表结构；业务检索 SQL 在 rag-knowledge 中实现。
 */
@SpringBootTest
@Testcontainers
class HybridSearchCapabilityTests {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = TestDatabase.container();

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void createSampleTable() {
        jdbc.execute("drop table if exists capability_probe");
        jdbc.execute("create table capability_probe (id bigint primary key, body text not null, embedding vector(3) not null)");
        jdbc.update("insert into capability_probe values (1, '收银员交班前需要完成现金盘点并签字', '[1,0,0]')");
        jdbc.update("insert into capability_probe values (2, '临期商品应在保质期前三天下架，并登记损耗', '[0,1,0]')");
        jdbc.update("insert into capability_probe values (3, '会员积分可以在收银台兑换指定商品', '[0,0,1]')");
        jdbc.execute("create index capability_probe_bm25 on capability_probe using bm25 (id, (body::pdb.jieba)) with (key_field = 'id')");
    }

    @Test
    @DisplayName("jieba 能把中文句子切成词")
    void jiebaSegmentsChineseText() {
        List<String> tokens = jdbc.queryForList("select unnest('临期商品下架'::pdb.jieba::text[])", String.class);
        assertThat(tokens).contains("临期", "商品");
    }

    @Test
    @DisplayName("中文关键词查询命中对应文档")
    void bm25FindsChineseKeywords() {
        List<Long> ids = jdbc.queryForList(
                "select id from capability_probe where body ||| '临期 下架' order by pdb.score(id) desc", Long.class);
        assertThat(ids).first().isEqualTo(2L);
    }

    @Test
    @DisplayName("向量距离排序正确")
    void vectorDistanceOrdering() {
        List<Long> ids = jdbc.queryForList(
                "select id from capability_probe order by embedding <=> '[0.9,0.1,0]'::vector limit 2", Long.class);
        assertThat(ids).containsExactly(1L, 2L);
    }
}
