package com.liming.rag;

import org.springframework.boot.autoconfigure.task.TaskExecutionAutoConfiguration;
import org.springframework.boot.task.SimpleAsyncTaskExecutorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.core.task.SimpleAsyncTaskExecutor;

/**
 * 后台任务的执行器（#135）：流式回答、会话轮次的保存、标题生成、检索规划和低分改写的超时控制都用它，每个任务一个虚拟线程。
 *
 * <p>用 Spring Boot 的 builder 建，虚拟线程和停机时等多久照常读 {@code spring.threads.virtual}、
 * {@code spring.task.execution.shutdown}。自己声明这个 bean 只为一件事：让它依赖数据源。停机时 Spring 先关依赖方，
 * 所以先等后台任务做完、再关连接池；否则连接池先关，正在保存的一轮会写库失败。
 */
@Configuration
class TaskExecutionConfiguration {

    @Bean(name = {TaskExecutionAutoConfiguration.APPLICATION_TASK_EXECUTOR_BEAN_NAME, "taskExecutor"})
    @DependsOn("dataSource")
    SimpleAsyncTaskExecutor applicationTaskExecutor(SimpleAsyncTaskExecutorBuilder builder) {
        return builder.build();
    }
}
