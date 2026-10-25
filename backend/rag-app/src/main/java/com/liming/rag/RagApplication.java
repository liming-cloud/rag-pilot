package com.liming.rag;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 唯一的启动类。放在 {@code com.liming.rag} 根包下，使各模块的子包都能被组件扫描和 Spring Modulith 识别。
 */
@SpringBootApplication
public class RagApplication {

    public static void main(String[] args) {
        SpringApplication.run(RagApplication.class, args);
    }
}
