package com.liming.rag.evaluation.application.suite;

import com.liming.rag.evaluation.application.EvaluationException;
import com.liming.rag.evaluation.application.Expectation;
import com.liming.rag.evaluation.application.MatchMode;
import com.liming.rag.shared.Enums;
import com.liming.rag.shared.Texts;
import org.jspecify.annotations.Nullable;

/** 标准题输入的校验和解析，写在一处（A-14）。 */
final class QuestionInputs {

    static final int MAX_QUESTION = 500;
    static final int MAX_NAME = 100;
    static final int MAX_DESCRIPTION = 500;

    private QuestionInputs() {
    }

    static String question(@Nullable String text) {
        var question = Texts.blankToNull(text);
        if (question == null) {
            throw EvaluationException.invalid("请填写问题");
        }
        var trimmed = question.strip();
        if (trimmed.length() > MAX_QUESTION) {
            throw EvaluationException.invalid("问题不能超过 " + MAX_QUESTION + " 个字");
        }
        return trimmed;
    }

    static Expectation expectation(@Nullable String value) {
        return Enums.parse(Expectation.class, value, () -> EvaluationException.invalid("期望只能是 DOCUMENTS 或 REFUSAL"));
    }

    static MatchMode match(@Nullable String value) {
        var parsed = Enums.parseOrNull(MatchMode.class, value, () -> EvaluationException.invalid("命中方式只能是 ALL 或 ANY"));
        return parsed == null ? MatchMode.ALL : parsed;
    }

    static String name(@Nullable String value) {
        var name = Texts.blankToNull(value);
        if (name == null) {
            throw EvaluationException.invalid("请填写题集名称");
        }
        if (name.strip().length() > MAX_NAME) {
            throw EvaluationException.invalid("题集名称不能超过 " + MAX_NAME + " 个字");
        }
        return name.strip();
    }

    static String description(@Nullable String value) {
        var description = value == null ? "" : value.strip();
        if (description.length() > MAX_DESCRIPTION) {
            throw EvaluationException.invalid("说明不能超过 " + MAX_DESCRIPTION + " 个字");
        }
        return description;
    }
}
