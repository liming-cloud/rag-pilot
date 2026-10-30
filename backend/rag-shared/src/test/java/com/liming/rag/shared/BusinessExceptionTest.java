package com.liming.rag.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BusinessExceptionTest {

    @Test
    @DisplayName("带分类、错误码和说明；原始异常只作为 cause 保留")
    void carriesKindCodeAndMessage() {
        var cause = new IllegalStateException("内部原因");
        var exception = new BusinessException(ErrorKind.CONFLICT, "KNOWLEDGE_CONFLICT", "已有同名知识库", cause);

        assertThat(exception.kind()).isEqualTo(ErrorKind.CONFLICT);
        assertThat(exception.code()).isEqualTo("KNOWLEDGE_CONFLICT");
        assertThat(exception.getMessage()).isEqualTo("已有同名知识库");
        assertThat(exception.getCause()).isSameAs(cause);
    }

    @Test
    @DisplayName("错误码必须是大写下划线格式，分类不能为空")
    void rejectsMalformedCodes() {
        assertThatThrownBy(() -> new BusinessException(ErrorKind.INVALID, "invalid-code", "说明"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BusinessException(ErrorKind.INVALID, null, "说明"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BusinessException(null, "CODE", "说明"))
                .isInstanceOf(NullPointerException.class);
    }
}
