package com.liming.rag;

import java.util.List;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.annotation.MergedAnnotations;
import org.springframework.test.context.ContextConfigurationAttributes;
import org.springframework.test.context.ContextCustomizer;
import org.springframework.test.context.ContextCustomizerFactory;
import org.springframework.test.context.MergedContextConfiguration;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 集成测试统一使用的数据库镜像，和 deploy/compose.yml 保持一致。所有 {@code @SpringBootTest} 共用一个容器
 * （{@link #shared()}，进程结束时由 Testcontainers 回收），配置相同的测试类因此共用一个上下文（#167）；每个测试类结束后
 * 由 {@link TestDatabaseReset} 恢复成干净的数据。
 * 在 {@code META-INF/spring.factories} 里登记，测试类不用声明。
 */
public final class TestDatabase {

    static final DockerImageName IMAGE =
            DockerImageName.parse("paradedb/paradedb@sha256:9b15fc383bcd771f1e32e222bd1603c4c29856c481f6bd06f1960e89608c02f0").asCompatibleSubstituteFor("postgres");

    private TestDatabase() {
    }

    /** 独立的新容器；要一个干净数据库、自己管理生命周期的测试（迁移、自己启动应用）用它。 */
    public static PostgreSQLContainer container() {
        return new PostgreSQLContainer(IMAGE);
    }

    /** 共用的容器，第一次取时启动。 */
    static PostgreSQLContainer shared() {
        return Shared.POSTGRES;
    }

    private static final class Shared {

        static final PostgreSQLContainer POSTGRES = start();

        private static PostgreSQLContainer start() {
            // 缓存着的上下文各有一个连接池，默认 100 个连接不够。
            var postgres = container().withCommand("postgres", "-c", "fsync=off", "-c", "max_connections=500");
            postgres.start();
            return postgres;
        }
    }

    static final class Factory implements ContextCustomizerFactory {

        @Override
        public ContextCustomizer createContextCustomizer(Class<?> testClass,
                List<ContextConfigurationAttributes> configAttributes) {
            return MergedAnnotations.from(testClass, MergedAnnotations.SearchStrategy.TYPE_HIERARCHY)
                    .isPresent(SpringBootTest.class) ? new Customizer() : null;
        }
    }

    /** 没有字段：相同配置的测试类照常共用一个上下文。 */
    private record Customizer() implements ContextCustomizer {

        @Override
        public void customizeContext(ConfigurableApplicationContext context, MergedContextConfiguration config) {
            var postgres = shared();
            TestPropertyValues.of(
                    "spring.datasource.url=" + postgres.getJdbcUrl(),
                    "spring.datasource.username=" + postgres.getUsername(),
                    "spring.datasource.password=" + postgres.getPassword()).applyTo(context);
        }
    }
}
