package com.liming.rag.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class InputsTest {

    enum Color {
        RED, GREEN
    }

    private static final Supplier<BusinessException> INVALID =
            () -> new BusinessException(ErrorKind.INVALID, "TEST_INVALID", "颜色只能是 RED 或 GREEN");

    @Test
    @DisplayName("空串和只有空白按没填，其余去掉首尾空白")
    void blankToNull() {
        assertThat(Texts.blankToNull(null)).isNull();
        assertThat(Texts.blankToNull("  ")).isNull();
        assertThat(Texts.blankToNull(" 冷柜 ")).isEqualTo("冷柜");
    }

    @Test
    @DisplayName("必填的枚举不区分大小写；没填或不认识时抛调用方给的业务异常")
    void parseRequired() {
        assertThat(Enums.parse(Color.class, "red", INVALID)).isEqualTo(Color.RED);
        assertThatThrownBy(() -> Enums.parse(Color.class, null, INVALID)).hasMessage("颜色只能是 RED 或 GREEN");
        assertThatThrownBy(() -> Enums.parse(Color.class, "", INVALID)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> Enums.parse(Color.class, "blue", INVALID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).code()).isEqualTo("TEST_INVALID");
    }

    @Test
    @DisplayName("可选的枚举没填时为 null，填了不认识的同样抛业务异常")
    void parseOptional() {
        assertThat(Enums.parseOrNull(Color.class, null, INVALID)).isNull();
        assertThat(Enums.parseOrNull(Color.class, " ", INVALID)).isNull();
        assertThat(Enums.parseOrNull(Color.class, "Green", INVALID)).isEqualTo(Color.GREEN);
        assertThatThrownBy(() -> Enums.parseOrNull(Color.class, "blue", INVALID))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("分页请求：页码小于 1 按 1，条数默认 20、最多 100；结果可以换元素类型")
    void paged() {
        assertThat(new Paged.Request(0, 0)).isEqualTo(new Paged.Request(1, Paged.DEFAULT_SIZE));
        assertThat(new Paged.Request(3, 500).size()).isEqualTo(Paged.MAX_SIZE);
        assertThat(new Paged.Request(3, 10).offset()).isEqualTo(20);
        assertThat(new Paged<>(List.of(1, 2), 12, 2, 10).map(String::valueOf))
                .isEqualTo(new Paged<>(List.of("1", "2"), 12, 2, 10));
    }
}
