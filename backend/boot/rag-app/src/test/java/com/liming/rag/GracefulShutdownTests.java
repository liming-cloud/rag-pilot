package com.liming.rag;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.DriverManager;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 停机时等后台任务做完（#135）：流式回答、会话轮次的保存、标题生成都在 {@code applicationTaskExecutor} 里跑，
 * 关闭应用时它最多等 30 秒，期间数据库连接还能用，正在生成的一轮能保存下来。
 *
 * <p>要真的关闭应用，所以自己启动一个应用上下文，不用测试框架缓存的那个。
 */
@Testcontainers
class GracefulShutdownTests {

    private static final Duration WORK = Duration.ofSeconds(2);

    @Container
    static final PostgreSQLContainer POSTGRES = TestDatabase.container();

    @Container
    static final GenericContainer<?> REDIS = TestRedis.container();

    @Test
    @DisplayName("关闭应用时等正在跑的后台任务做完，它还能写库")
    void waitsForBackgroundWorkOnShutdown() throws Exception {
        var arguments = new java.util.ArrayList<>(List.of("--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                "--spring.datasource.username=" + POSTGRES.getUsername(),
                "--spring.datasource.password=" + POSTGRES.getPassword(),
                "--rag.knowledge.storage.root=${java.io.tmpdir}/rag-shutdown-test-files"));
        arguments.addAll(TestRedis.arguments(REDIS));
        var context = new SpringApplicationBuilder(RagApplication.class)
                .web(WebApplicationType.NONE)
                .run(arguments.toArray(String[]::new));
        var executor = context.getBean("applicationTaskExecutor", AsyncTaskExecutor.class);
        var jdbc = context.getBean(JdbcTemplate.class);
        jdbc.execute("CREATE TABLE shutdown_probe (note text)");
        var started = new CountDownLatch(1);
        executor.execute(() -> {
            started.countDown();
            try {
                Thread.sleep(WORK);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            jdbc.update("INSERT INTO shutdown_probe VALUES ('saved')");
        });
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

        long start = System.nanoTime();
        context.close();
        long waited = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertThat(waited).as("关闭时等了后台任务").isGreaterThanOrEqualTo(WORK.toMillis() / 2);
        try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword());
                var rows = connection.createStatement().executeQuery("SELECT count(*) FROM shutdown_probe")) {
            rows.next();
            assertThat(rows.getInt(1)).as("后台任务在停机过程中写进了库").isEqualTo(1);
        }
    }
}
