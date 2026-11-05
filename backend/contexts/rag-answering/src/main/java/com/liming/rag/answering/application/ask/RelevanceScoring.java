package com.liming.rag.answering.application.ask;

import com.liming.rag.answering.application.ModelUnavailableException;
import java.util.List;

/** 重排端口（D-054）：给每段候选文本打一个与问题的相关度分数（0 到 1，越大越相关）。 */
public interface RelevanceScoring {

    /**
     * @return 与 {@code documents} 一一对应、顺序相同的分数
     * @throws ModelUnavailableException 重排模型不可用
     */
    List<Double> score(String query, List<String> documents);
}
