package com.liming.rag.archfixture.domain;

/** 违规样例：领域对象用 IllegalArgumentException 报规则违规，要靠 application 逐处翻译（D-067）。 */
public class DomainThrowsIllegalArgument {

    void rename(String name) {
        if (name.isBlank()) {
            throw new IllegalArgumentException("名称不能为空");
        }
    }
}
