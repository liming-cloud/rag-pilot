package com.liming.rag.knowledge.domain;

import com.liming.rag.shared.BusinessException;
import com.liming.rag.shared.ErrorKind;
import java.io.Serial;

/**
 * 知识领域对象的规则违规，直接带错误码，由入口统一转换，application 不用再翻译（D-067）。错误码与 application 的
 * {@code KnowledgeException} 是同一套 {@code KNOWLEDGE_<原因>}，message 是给用户看的中文说明。
 */
public class KnowledgeRuleException extends BusinessException {

    @Serial
    private static final long serialVersionUID = 1L;

    protected KnowledgeRuleException(ErrorKind kind, String reason, String message) {
        super(kind, "KNOWLEDGE_" + reason, message);
    }

    /** 输入不符合规则（{@code KNOWLEDGE_INVALID}）。 */
    public static KnowledgeRuleException invalid(String message) {
        return new KnowledgeRuleException(ErrorKind.INVALID, "INVALID", message);
    }
}
