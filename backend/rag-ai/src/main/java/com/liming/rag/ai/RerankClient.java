package com.liming.rag.ai;

import java.util.List;

/** 重排：给每段候选文本打一个与问题的相关度分数（0 到 1，越大越相关）。 */
public interface RerankClient {

    /** 返回的分数与 {@code documents} 一一对应、顺序相同。 */
    List<Double> score(String query, List<String> documents);
}
