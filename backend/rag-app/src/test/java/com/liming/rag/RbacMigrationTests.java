package com.liming.rag;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 现有账号迁到 RBAC（D-038）：最早创建的 ADMIN 成为超级管理员，其余 ADMIN 成为“管理员”，STAFF 成为“员工”；
 * 逐人授权原样保留，迁移后原来能做的事仍能做。先迁到 RBAC 之前的版本、按旧表结构写数据，再迁到最新。
 */
@Testcontainers
class RbacMigrationTests {

    /** RBAC 迁移（V202610300301）之前的最后一个迁移。 */
    private static final String BEFORE_RBAC = "202610300101";

    @Container
    static final PostgreSQLContainer POSTGRES = TestDatabase.container();

    @Test
    @DisplayName("旧账号按规则迁到内置角色，逐人授权保留，每个租户都有三个内置角色")
    void migratesExistingAccounts() {
        var dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword());
        var jdbc = new JdbcTemplate(dataSource);
        flyway(dataSource, BEFORE_RBAC).migrate();
        jdbc.update("""
                INSERT INTO app_user (tenant_id, username, display_name, password_hash, role, created_at) VALUES
                  (1, 'second-admin', '后来的管理员', 'x', 'ADMIN', now()),
                  (1, 'first-admin', '最早的管理员', 'x', 'ADMIN', now() - interval '30 days'),
                  (1, 'staff01', '店员', 'x', 'STAFF', now()),
                  (2, 'other-admin', '另一租户管理员', 'x', 'ADMIN', now())""");
        long kb = jdbc.queryForObject("INSERT INTO knowledge_base (tenant_id, name) VALUES (1, '门店库') RETURNING id",
                Long.class);
        long staff = userId(jdbc, "staff01");
        jdbc.update("INSERT INTO knowledge_base_member (tenant_id, knowledge_base_id, user_id) VALUES (1, ?, ?)",
                kb, staff);

        flyway(dataSource, null).migrate();

        assertThat(builtInOf(jdbc, "first-admin")).isEqualTo("SUPER_ADMIN");
        assertThat(builtInOf(jdbc, "second-admin")).isEqualTo("ADMIN");
        assertThat(builtInOf(jdbc, "staff01")).isEqualTo("STAFF");
        assertThat(builtInOf(jdbc, "other-admin")).as("全系统只有一个超级管理员").isEqualTo("ADMIN");
        assertThat(jdbc.queryForList("SELECT tenant_id FROM app_role GROUP BY tenant_id HAVING count(*) = 3",
                Long.class)).containsExactlyInAnyOrder(1L, 2L);

        assertThat(permissionsOf(jdbc, "second-admin"))
                .contains("kb:create", "doc:upload", "kb:grant", "user:create", "user:disable", "answer:review",
                        "stats:view", "chat:use")
                .doesNotContain("role:view", "model:view", "setting:view");
        assertThat(permissionsOf(jdbc, "staff01")).containsExactly("chat:use");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM knowledge_base_member WHERE user_id = ?", Integer.class,
                staff)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM information_schema.columns "
                + "WHERE table_name = 'app_user' AND column_name = 'role'", Integer.class)).isZero();
    }

    private static Flyway flyway(DriverManagerDataSource dataSource, String target) {
        var configuration = Flyway.configure().dataSource(dataSource).locations("classpath:db/migration/identity",
                "classpath:db/migration/knowledge", "classpath:db/migration/answering",
                "classpath:db/migration/evaluation");
        if (target != null) {
            configuration.target(target);
        }
        return configuration.load();
    }

    private static long userId(JdbcTemplate jdbc, String username) {
        return jdbc.queryForObject("SELECT id FROM app_user WHERE username = ?", Long.class, username);
    }

    private static String builtInOf(JdbcTemplate jdbc, String username) {
        return jdbc.queryForObject("""
                SELECT r.builtin FROM app_user_role ur JOIN app_role r ON r.id = ur.role_id
                JOIN app_user u ON u.id = ur.user_id WHERE u.username = ?""", String.class, username);
    }

    private static List<String> permissionsOf(JdbcTemplate jdbc, String username) {
        return jdbc.queryForList("""
                SELECT p.permission FROM app_role_permission p JOIN app_user_role ur ON ur.role_id = p.role_id
                JOIN app_user u ON u.id = ur.user_id WHERE u.username = ? ORDER BY p.permission""", String.class,
                username);
    }
}
