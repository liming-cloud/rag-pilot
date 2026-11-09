package com.liming.rag;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.core.Violations;

/**
 * 模块边界（Spring Modulith）：只通过开放模块和 api 包依赖，没有循环依赖，并且每个上下文只依赖它在 {@code package-info}
 * 里声明的模块（{@code allowedDependencies}，方向见 规则卡 A-10，#136）。
 */
class ModularityTests {

    @Test
    @DisplayName("模块之间只通过开放模块和 api 包依赖，没有循环依赖，依赖方向与声明一致")
    void verifiesModuleBoundaries() {
        ApplicationModules.of(RagApplication.class).verify();
    }

    @Test
    @DisplayName("依赖方向的检查能拦下样例：声明不依赖任何模块的上游引用了下游的 api")
    void catchesReverseDependency() {
        var fixture = ApplicationModules.of("com.liming.archmodules", location -> true);

        assertThatThrownBy(fixture::verify).isInstanceOf(Violations.class).hasMessageContaining("upstream")
                .hasMessageContaining("downstream");
    }

    @Test
    @DisplayName("能力组件的子包默认不对外：只能用根包接口和标了 @NamedInterface 的契约，引用内部类型被拦下")
    void catchesInternalOfCapability() {
        var fixture = ApplicationModules.of("com.liming.archcapability", location -> true);

        assertThatThrownBy(fixture::verify).isInstanceOf(Violations.class).hasMessageContaining("Internal")
                .hasMessageNotContaining("Contract").hasMessageNotContaining("Capability.");
    }
}
