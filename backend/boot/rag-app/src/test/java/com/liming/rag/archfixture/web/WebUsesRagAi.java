package com.liming.rag.archfixture.web;

import com.liming.rag.ai.ModelProbe;
import com.liming.rag.ai.common.ModelUsage;

/** 违规样例（D-054）：web 层直接用 rag-ai 的连通测试，应经 application 的端口。 */
public class WebUsesRagAi {

    private final ModelProbe probe;

    WebUsesRagAi(ModelProbe probe) {
        this.probe = probe;
    }

    boolean probe() {
        return probe.probe(ModelUsage.ANSWER, probe.environment(ModelUsage.ANSWER)).ok();
    }
}
