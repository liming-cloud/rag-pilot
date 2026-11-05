package com.liming.rag;

import java.util.List;
import org.redisson.api.RedissonClient;
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
 * 上下文关闭时停掉：各测试类的数据库是各自的，缓存和会话也不能串。容器注册成 bean {@value #BEAN}，要模拟 Redis
 * 停掉的测试取出来停掉（并标 {@code @DirtiesContext}）。在 {@code META-INF/spring.factories} 里登记，测试类不用声明。
 */
final class TestRedis {

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
        return List.of("--spring.data.redis.host=" + redis.getHost(),
                "--spring.data.redis.port=" + redis.getMappedPort(6379),
                "--spring.data.redis.password=" + PASSWORD);
    }

    /** 测试绕过应用直接改了库里被缓存的表（账号、角色、设置、模型）时，清掉全部缓存，不动会话。 */
    static void clearCaches(RedissonClient redis) {
        redis.getKeys().deleteByPattern("rag:cache:*");
    }

    static final class Factory implements ContextCustomizerFactory {

        @Override
        public ContextCustomizer createContextCustomizer(Class<?> testClass,
                List<ContextConfigurationAttributes> configAttributes) {
            var springBootTest = MergedAnnotations.from(testClass, MergedAnnotations.SearchStrategy.TYPE_HIERARCHY)
                    .isPresent(SpringBootTest.class);
            return springBootTest ? new Customizer() : null;
        }
    }

    /** 没有字段：相同配置的测试类照常共用一个上下文（和它的 Redis）。 */
    private record Customizer() implements ContextCustomizer {

        @Override
        public void customizeContext(ConfigurableApplicationContext context, MergedContextConfiguration config) {
            var redis = container();
            redis.start();
            TestPropertyValues.of(
                    "spring.data.redis.host=" + redis.getHost(),
                    "spring.data.redis.port=" + redis.getMappedPort(6379),
                    "spring.data.redis.password=" + PASSWORD).applyTo(context);
            context.getBeanFactory().registerSingleton(BEAN, redis);
            context.addApplicationListener(event -> {
                if (event instanceof ContextClosedEvent) {
                    redis.stop();
                }
            });
        }
    }
}
