package com.liming.rag;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.util.LinkedMultiValueMap;

/**
 * 一次问答的 SQL（#145 第 9 步，D-054）：缓存热了之后，权限、设置、模型路由和问题向量都不再查库，条数不超过上限，防止以后
 * 退化。数的是请求处理中经连接发出的语句（事务的开始和提交不算）；定时任务（入库轮询等）的线程不算。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class AskSqlBudgetTests {

    /** 新建会话 2 条加一轮问答 14 条（检索、记下回答和引用、更新会话、补存问题向量），2026-11-05 实测。 */
    static final int BUDGET = 16;
    /** 权限、系统设置、模型路由的表：缓存热了之后不该出现。 */
    static final List<String> CACHED_TABLES = List.of("app_user", "app_role", "app_role_permission", "app_user_role",
            "system_setting", "model_purpose", "model_provider");

    @Autowired
    private TestRestTemplate http;

    @Autowired
    private StatementLog statements;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void signIn() {
        TestLogin.asAdmin(http);
    }

    @Test
    @DisplayName("缓存热了之后，一次问答不再查权限、设置和模型路由，SQL 不超过上限")
    void askStaysWithinSqlBudget() throws InterruptedException {
        var created = http.postForEntity("/api/knowledge-bases", Map.of("name", "语句数知识库"), Map.class);
        long store = ((Number) created.getBody().get("id")).longValue();
        upload(store, "商品陈列.txt", "青柚气泡水陈列在一层 A 区 03 号货架第二层。补货时先移出临期批次。");
        var question = "青柚气泡水在哪个货架";
        assertThat(TestAsk.ask(http, null, List.of(store), question)).containsEntry("refused", false);
        // 预热那一问的问题向量在后台补存（#160）：等它存好再开始数，不算进这一问。
        waitForQuestionVectors();

        statements.reset();
        assertThat(TestAsk.ask(http, null, List.of(store), question)).containsEntry("refused", false);
        var issued = statements.issued();

        var listed = String.join("\n", issued);
        assertThat(issued).as("这次问答发出的语句：%n%s", listed).hasSizeLessThanOrEqualTo(BUDGET);
        for (var table : CACHED_TABLES) {
            assertThat(issued).as("缓存热了之后不该查 %s：%n%s", table, listed)
                    .noneMatch(sql -> sql.matches("(?is).*\\b" + table + "\\b.*"));
        }
    }

    private void waitForQuestionVectors() throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            if (jdbc.queryForObject("SELECT count(*) FROM answer_record WHERE question_embedding IS NULL",
                    Long.class) == 0) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("预热那一问的问题向量 5 秒内没有存好");
    }

    @SuppressWarnings("unchecked") // 上传响应按原始 Map 读。
    private void upload(long knowledgeBaseId, String filename, String text) {
        var file = new ByteArrayResource(text.getBytes(StandardCharsets.UTF_8)) {
            @Override
            public String getFilename() {
                return filename;
            }
        };
        var body = new LinkedMultiValueMap<String, Object>();
        body.add("file", file);
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        var response = http.postForEntity("/api/knowledge-bases/{id}/documents", new HttpEntity<>(body, headers),
                Map.class, knowledgeBaseId);
        assertThat(TestDocuments.awaitProcessed(http, knowledgeBaseId, response.getBody(), null))
                .containsEntry("status", "READY");
    }

    /** 记下经连接发出的语句（预编译和普通语句），带上线程名；定时任务的线程不记。 */
    static final class StatementLog {

        private final ConcurrentLinkedQueue<String> issued = new ConcurrentLinkedQueue<>();

        void reset() {
            issued.clear();
        }

        List<String> issued() {
            return List.copyOf(issued);
        }

        void record(String sql) {
            var thread = Thread.currentThread().getName();
            if (!thread.startsWith("scheduling")) {
                issued.add("[" + thread + "] " + sql.strip().replaceAll("\\s+", " "));
            }
        }

        Connection wrap(Connection connection) {
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                    new Class<?>[] {Connection.class}, (proxy, method, args) -> {
                        if (method.getName().equals("prepareStatement") || method.getName().equals("prepareCall")) {
                            record((String) args[0]);
                        } else if (method.getName().equals("createStatement")) {
                            record("（普通语句）");
                        }
                        try {
                            return method.invoke(connection, args);
                        } catch (InvocationTargetException e) {
                            throw e.getCause();
                        }
                    });
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class CountStatements {

        @Bean
        StatementLog statementLog() {
            return new StatementLog();
        }

        @Bean
        static BeanPostProcessor countingDataSource(ObjectProvider<StatementLog> log) {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) {
                    if (!(bean instanceof DataSource dataSource)) {
                        return bean;
                    }
                    return new DelegatingDataSource(dataSource) {
                        @Override
                        public Connection getConnection() throws SQLException {
                            return log.getObject().wrap(super.getConnection());
                        }

                        @Override
                        public Connection getConnection(String username, String password) throws SQLException {
                            return log.getObject().wrap(super.getConnection(username, password));
                        }
                    };
                }
            };
        }
    }
}
