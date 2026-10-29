package com.liming.rag.archfixture.api;

import com.liming.rag.archfixture.domain.FixtureEntity;

/** 违规样例：对外合同暴露了模块内部的领域类型。 */
public record ApiLeaksDomain(FixtureEntity entity) {
}
