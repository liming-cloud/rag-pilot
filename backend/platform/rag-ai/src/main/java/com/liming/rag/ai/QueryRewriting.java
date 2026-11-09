package com.liming.rag.ai;

import com.liming.rag.ai.common.ModelCallException;

/**
 * 问题改写能力（Spring AI 防腐层，#145）：把员工的问题改写成更适合检索的一句话，内部用 Spring AI 的 {@code ChatClient}。
 * 改写规则是调用方的策略（行业包的角色和提示加内核规则），由 {@code instructions} 给出；
 * 这里只负责调用回答模型、把输出限制在一句话（最多 64 个 token，遇换行就停），不判断改写好不好。
 *
 * <p>用法：{@code rewrite(规则, 问题)}，返回改写后的一句话，空串表示模型没给出。失败：模型不可用时抛 {@link ModelCallException}。
 */
public interface QueryRewriting {

    /**
     * @return 模型给出的改写，去掉首尾空白；模型什么也没给时为空串
     * @throws ModelCallException 模型不可用
     */
    String rewrite(String instructions, String question);
}
