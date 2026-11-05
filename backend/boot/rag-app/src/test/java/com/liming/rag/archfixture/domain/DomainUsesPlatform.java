package com.liming.rag.archfixture.domain;

import com.liming.rag.persistence.Pages;
import com.liming.rag.shared.Paged;

/** 违规样例（D-054）：领域对象依赖平台组件 rag-persistence。 */
public class DomainUsesPlatform {

    Object page(Paged.Request request) {
        return Pages.of(request);
    }
}
