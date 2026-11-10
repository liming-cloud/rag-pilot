package com.liming.rag.knowledge.application.ingestion;

import com.liming.rag.knowledge.application.KnowledgeProperties;
import java.time.Duration;
import org.springframework.boot.task.ThreadPoolTaskExecutorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 后台入库的执行器（#197）：线程数是 {@code rag.knowledge.ingestion.concurrency}，限制对嵌入模型的并发调用。
 *
 * <p>用 Spring Boot 的 builder 建，停机时等正在处理的文档做完；依赖数据源，所以先等、再关连接池（同
 * {@code applicationTaskExecutor}，#135）。只等 {@link #SHUTDOWN_WAIT}：Compose 的 {@code stop_grace_period}（75 秒）
 * 要容下 Web 和 {@code applicationTaskExecutor} 各 30 秒；没做完的文档仍是“解析中”，下次启动时重新排队。
 */
@Configuration
class IngestionExecutorConfiguration {

    static final String BEAN_NAME = "ingestionTaskExecutor";
    static final Duration SHUTDOWN_WAIT = Duration.ofSeconds(10);

    @Bean(BEAN_NAME)
    @DependsOn("dataSource")
    ThreadPoolTaskExecutor ingestionTaskExecutor(ThreadPoolTaskExecutorBuilder builder, KnowledgeProperties properties) {
        int concurrency = properties.ingestion().concurrency();
        return builder.corePoolSize(concurrency).maxPoolSize(concurrency).threadNamePrefix("ingestion-")
                .awaitTermination(true).awaitTerminationPeriod(SHUTDOWN_WAIT).build();
    }
}
