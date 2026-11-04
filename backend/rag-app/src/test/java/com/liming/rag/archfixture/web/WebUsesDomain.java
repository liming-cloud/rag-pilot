package com.liming.rag.archfixture.web;

import com.liming.rag.archfixture.domain.FixtureAggregate;

/** 违规样例：Controller 直接拿聚合拼视图，应该由 application 给出查询结果。 */
public class WebUsesDomain {

    Object view(FixtureAggregate aggregate) {
        return aggregate;
    }
}
