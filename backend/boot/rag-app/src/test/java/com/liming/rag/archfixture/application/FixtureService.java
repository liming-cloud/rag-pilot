package com.liming.rag.archfixture.application;

/** 违规样例的配套：Service 里带一个内部 record，供 web 引用。 */
public class FixtureService {

    public record View(String name) {
    }

    public View view() {
        return new View("x");
    }
}
