package com.liming.rag.answering.application;

import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.SimpleAsyncTaskExecutor;

/** 测试用的虚拟线程执行器，代替生产环境里 Spring 管理的 {@code applicationTaskExecutor}。 */
final class TestExecutor {

    static final AsyncTaskExecutor VIRTUAL = virtual();

    private TestExecutor() {
    }

    private static AsyncTaskExecutor virtual() {
        var executor = new SimpleAsyncTaskExecutor("test-");
        executor.setVirtualThreads(true);
        return executor;
    }
}
