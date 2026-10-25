package com.liming.rag;

import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 集成测试统一使用的数据库镜像，和 deploy/compose.yml 保持一致。
 */
final class TestDatabase {

    static final DockerImageName IMAGE =
            DockerImageName.parse("paradedb/paradedb:latest").asCompatibleSubstituteFor("postgres");

    private TestDatabase() {
    }

    static PostgreSQLContainer container() {
        return new PostgreSQLContainer(IMAGE);
    }
}
