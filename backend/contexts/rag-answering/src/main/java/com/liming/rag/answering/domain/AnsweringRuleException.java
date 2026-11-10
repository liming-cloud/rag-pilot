package com.liming.rag.answering.domain;

import com.liming.rag.shared.BusinessException;
import com.liming.rag.shared.ErrorKind;
import java.io.Serial;

/**
 * 问答领域对象的规则违规，直接带错误码，由入口统一转换，application 不用再翻译（D-067）。错误码与 application 的
 * {@code AnsweringException} 是同一套 {@code ANSWERING_<原因>}，message 是给用户看的中文说明。
 */
public class AnsweringRuleException extends BusinessException {

    @Serial
    private static final long serialVersionUID = 1L;

    protected AnsweringRuleException(ErrorKind kind, String reason, String message) {
        super(kind, "ANSWERING_" + reason, message);
    }

    /** 输入不符合规则（{@code ANSWERING_INVALID}）。 */
    public static AnsweringRuleException invalid(String message) {
        return new AnsweringRuleException(ErrorKind.INVALID, "INVALID", message);
    }
}
