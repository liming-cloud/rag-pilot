package com.liming.rag;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

class ModularityTests {

    @Test
    @DisplayName("模块之间只通过开放模块和 api 包依赖，且没有循环依赖")
    void verifiesModuleBoundaries() {
        ApplicationModules.of(RagApplication.class).verify();
    }
}
