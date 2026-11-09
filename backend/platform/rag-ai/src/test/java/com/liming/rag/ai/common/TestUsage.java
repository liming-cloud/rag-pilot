package com.liming.rag.ai.common;

import com.liming.rag.ai.UsageRecording;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

/** 测试用的用量记录：把 {@link UsageRecording} 收到的每次调用留在 {@link #calls}，并给各能力的实现一个 {@link UsageReports}。 */
public final class TestUsage implements UsageRecording {

    public final List<ModelCallUsage> calls = new CopyOnWriteArrayList<>();

    @Override
    public void record(ModelCallUsage usage) {
        calls.add(usage);
    }

    public UsageReports reports() {
        var factory = new StaticListableBeanFactory();
        factory.addBean("recording", this);
        return new UsageReports(factory.getBeanProvider(UsageRecording.class));
    }

    /** 没有任何记录实现：什么也不记。 */
    public static UsageReports none() {
        return new UsageReports(new StaticListableBeanFactory().getBeanProvider(UsageRecording.class));
    }
}
