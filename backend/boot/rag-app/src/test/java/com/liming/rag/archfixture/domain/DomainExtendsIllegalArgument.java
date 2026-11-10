package com.liming.rag.archfixture.domain;

import java.io.Serial;

/** 违规样例：领域异常继承 IllegalArgumentException，同样要靠 application 翻译（D-067）。 */
public class DomainExtendsIllegalArgument extends IllegalArgumentException {

    @Serial
    private static final long serialVersionUID = 1L;

    DomainExtendsIllegalArgument() {
        super("类型不支持");
    }
}
