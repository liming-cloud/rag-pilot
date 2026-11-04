package com.liming.rag.ai;

/**
 * 问题改写能力（Spring AI 防腐层，#145）：把员工的问题改写成更适合检索的一句话，内部用 Spring AI 的 {@code ChatClient}。
 * 改写规则是调用方的策略（行业包的角色和提示加内核规则），由 {@code instructions} 给出；
 * 这里只负责调用回答模型、把输出限制在一句话（最多 64 个 token，遇换行就停），不判断改写好不好。
 */
public interface QueryRewriting {

    /**
     * @return 模型给出的改写，去掉首尾空白；模型什么也没给时为空串
     * @throws ModelCallException 模型不可用
     */
    String rewrite(String instructions, String question);
}
