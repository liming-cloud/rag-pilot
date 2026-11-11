package com.liming.rag;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.List;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.core.annotation.MergedAnnotations;
import org.springframework.test.context.ContextConfigurationAttributes;
import org.springframework.test.context.ContextCustomizer;
import org.springframework.test.context.ContextCustomizerFactory;
import org.springframework.test.context.MergedContextConfiguration;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 集成测试的 Redis（D-053），镜像和 deploy/compose.yml 一致。每个 {@code @SpringBootTest} 的 Spring 上下文各起一个，
 * 上下文关闭时停掉：各上下文的缓存和会话不能串，每个测试类结束时由 {@link TestDatabaseReset} 清空。容器注册成 bean
 * {@value #BEAN}，要模拟 Redis 停掉的测试取出来停掉（并标 {@code @DirtiesContext}）。在 {@code META-INF/spring.factories} 里登记，测试类不用声明。
 */
final class TestRedis {

    /** 标在测试类上：不起 Redis，按默认的本地模式（缓存只有一级、会话在进程内）启动。 */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE)
    @interface WithoutRedis {
    }

    static final DockerImageName IMAGE =
            DockerImageName.parse("redis@sha256:489a769e0a54dc245896132a15c72d2bc7296719954da99398f43a3877e5f8eb");
    static final String BEAN = "testRedisContainer";
    /** 和部署一样带密码启动。 */
    static final String PASSWORD = "test-redis-password";

    private TestRedis() {
    }

    /** 带密码的 Redis 容器；自己启动应用的测试（不走 {@code @SpringBootTest}）用它。 */
    static GenericContainer<?> container() {
        return new GenericContainer<>(IMAGE).withExposedPorts(6379).withCommand("redis-server", "--requirepass", PASSWORD);
    }

    /** 连到这个容器的配置项，按命令行参数的写法。 */
    static List<String> arguments(GenericContainer<?> redis) {
        return List.of("--rag.cache.redis.enabled=true",
                "--rag.cache.redis.host=" + redis.getHost(),
                "--rag.cache.redis.port=" + redis.getMappedPort(6379),
                "--rag.cache.redis.password=" + PASSWORD);
    }

    static final class Factory implements ContextCustomizerFactory {

        @Override
        public ContextCustomizer createContextCustomizer(Class<?> testClass,
                List<ContextConfigurationAttributes> configAttributes) {
            var annotations = MergedAnnotations.from(testClass, MergedAnnotations.SearchStrategy.TYPE_HIERARCHY);
            return annotations.isPresent(SpringBootTest.class) && !annotations.isPresent(WithoutRedis.class)
                    ? new Customizer() : null;
        }
    }

    /** 没有字段：相同配置的测试类照常共用一个上下文（和它的 Redis）。 */
    private record Customizer() implements ContextCustomizer {

        @Override
        public void customizeContext(ConfigurableApplicationContext context, MergedContextConfiguration config) {
            var redis = container();
            redis.start();
            TestPropertyValues.of(
                    "rag.cache.redis.enabled=true",
                    "rag.cache.redis.host=" + redis.getHost(),
                    "rag.cache.redis.port=" + redis.getMappedPort(6379),
                    "rag.cache.redis.password=" + PASSWORD).applyTo(context);
            context.getBeanFactory().registerSingleton(BEAN, redis);
            context.addApplicationListener(event -> {
                if (event instanceof ContextClosedEvent) {
                    redis.stop();
                }
            });
        }
    }
}
