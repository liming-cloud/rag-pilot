package com.liming.rag.archfixture.application;

import com.liming.rag.archfixture.infrastructure.FixtureRepository;

/** 违规样例：用例服务直接依赖 infrastructure 的实现，而不是自己定义的端口接口。 */
public class ApplicationUsesInfrastructure {

    private final FixtureRepository repository = new FixtureRepository();
}
