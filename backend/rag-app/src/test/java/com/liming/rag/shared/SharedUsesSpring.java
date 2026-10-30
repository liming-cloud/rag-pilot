package com.liming.rag.shared;

import org.springframework.util.Assert;

/** 违规样例：共享内核依赖了框架。共享内核只依赖 JDK。 */
public record SharedUsesSpring(String name) {

    public SharedUsesSpring {
        Assert.hasText(name, "name");
    }
}
