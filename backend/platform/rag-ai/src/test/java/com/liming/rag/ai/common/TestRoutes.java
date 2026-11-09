package com.liming.rag.ai.common;

import com.liming.rag.ai.ModelRouting;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

/** 测试用的 {@link ModelRoutes}：{@code overrides} 里有的用途按它走，其余用环境变量配置。可以在测试中途修改。 */
public final class TestRoutes {

    private TestRoutes() {
    }

    public static ModelRoutes of(AiProperties properties, Map<ModelUsage, ModelRoute> overrides) {
        var factory = new StaticListableBeanFactory();
        factory.addBean("routing", (ModelRouting) usage -> Optional.ofNullable(overrides.get(usage)));
        return new ModelRoutes(properties, factory.getBeanProvider(ModelRouting.class));
    }

    /** 没有系统设置模块时：全部用环境变量。 */
    public static ModelRoutes environmentOnly(AiProperties properties) {
        return new ModelRoutes(properties, new StaticListableBeanFactory().getBeanProvider(ModelRouting.class));
    }
}
