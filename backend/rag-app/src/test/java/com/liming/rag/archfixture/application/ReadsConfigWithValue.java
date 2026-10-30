package com.liming.rag.archfixture.application;

import org.springframework.beans.factory.annotation.Value;

/** 违规样例：用 @Value 零散读配置，而不是本模块的 @ConfigurationProperties。 */
public class ReadsConfigWithValue {

    private final int limit;

    ReadsConfigWithValue(@Value("${rag.sample.limit:3}") int limit) {
        this.limit = limit;
    }
}
