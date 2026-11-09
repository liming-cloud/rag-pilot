package com.liming.rag.archfixture.api;

import com.liming.rag.ai.common.ModelRoute;

/** 违规样例（D-054）：对其他上下文公开的合同带平台组件的类型，签名应只用本上下文的类型。 */
public interface ApiExposesPlatformType {

    ModelRoute judgeModel();
}
