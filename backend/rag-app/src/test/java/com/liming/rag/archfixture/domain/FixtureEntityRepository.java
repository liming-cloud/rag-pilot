package com.liming.rag.archfixture.domain;

/** 违规样例：为不是聚合根的 FixtureEntity 建了仓储。 */
public interface FixtureEntityRepository {

    FixtureEntity find(long id);
}
