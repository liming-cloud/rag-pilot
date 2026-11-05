package com.liming.rag.archfixture.web;

import com.liming.rag.archfixture.infrastructure.FixtureRepository;

/** 违规样例：Controller 直接依赖 infrastructure，绕过 application。 */
public class WebUsesInfrastructure {

    private final FixtureRepository repository = new FixtureRepository();
}
