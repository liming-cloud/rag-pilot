package com.liming.rag.archfixture.domain;

import org.springframework.util.Assert;

/** 违规样例：领域对象依赖 Spring。 */
public class DomainUsesSpring {

    void check(String name) {
        Assert.hasText(name, "name");
    }
}
