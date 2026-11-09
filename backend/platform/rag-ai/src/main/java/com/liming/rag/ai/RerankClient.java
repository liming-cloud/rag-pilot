package com.liming.rag.ai;

import java.util.List;

/**
 * 重排能力：给每段候选文本打一个与问题的相关度分数（0 到 1，越大越相关）。
 *
 * <p>用法：{@code score(问题, 候选文本列表)}，返回与候选一一对应的分数，由调用方决定怎么排序、取舍。
 * 失败：重排服务不可用或返回的分数个数不对时抛 {@link ModelCallException}。
 */
public interface RerankClient {

    /** 返回的分数与 {@code documents} 一一对应、顺序相同。 */
    List<Double> score(String query, List<String> documents);
}
