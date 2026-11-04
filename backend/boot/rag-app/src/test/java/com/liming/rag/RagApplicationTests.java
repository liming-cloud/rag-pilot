package com.liming.rag;

import static org.assertj.core.api.Assertions.assertThat;

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
 * 用真实 PostgreSQL 启动整个应用：验证各模块能装配、Flyway 迁移能执行、向量与 BM25 扩展可用。
 */
@SpringBootTest
@Testcontainers
class RagApplicationTests {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = TestDatabase.container();

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("应用启动后完成迁移，向量与 BM25 扩展已启用")
    void startsAndMigrates() {
        Integer extensions = jdbcTemplate.queryForObject(
                "select count(*) from pg_extension where extname in ('vector', 'pg_search')", Integer.class);
        assertThat(extensions).isEqualTo(2);
    }
}
