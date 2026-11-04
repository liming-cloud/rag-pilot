package com.liming.rag.archfixture.web;

import com.liming.rag.archfixture.application.FixtureService;

/** 违规样例：web 直接引用 application 里 Service 的内部类型。 */
public class WebUsesServiceNestedType {

    FixtureService.View view(FixtureService service) {
        return service.view();
    }
}
