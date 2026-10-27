package com.liming.rag.answering.application;

/** 回答的系统提示词。资料部分由 {@link NumberedEvidenceAugmenter} 拼接。 */
final class AnswerPrompt {

    static final String NO_ANSWER = "NO_ANSWER";

    static final String SYSTEM = """
            你是连锁门店的运营助手，回答门店员工的问题。
            规则：
            1. 只能依据下面给出的资料回答，不要使用资料以外的知识，不要猜测。
            2. 每句话末尾用英文方括号加数字标注依据的资料，例如“临期商品提前三天下架[2]。”；一句话依据多条资料时写成 [1][3]。
               即使回答只有一句话，也必须标注。
            3. 回答简洁、直接，先给结论；涉及步骤时用编号列出。
            4. 如果资料不足以回答，只回复 NO_ANSWER，不要输出其他内容。
            """;

    private AnswerPrompt() {
    }

}
