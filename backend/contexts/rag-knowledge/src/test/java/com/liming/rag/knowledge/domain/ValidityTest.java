package com.liming.rag.knowledge.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** 文档有效期（D-046）：失效日期当天仍有效，未到生效日期的不参与；即将到期按提前天数算。 */
class ValidityTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 2);

    private static LocalDate date(String value) {
        return value == null || value.isBlank() ? null : LocalDate.parse(value);
    }

    @ParameterizedTest(name = "{0} ~ {1} → {2}，可检索 {3}")
    @CsvSource(nullValues = "-", value = {
        "-,          -,          ACTIVE,   true",
        "-,          2026-11-02, EXPIRING, true",
        "-,          2026-11-01, EXPIRED,  false",
        "-,          2026-12-02, EXPIRING, true",
        "-,          2026-12-03, ACTIVE,   true",
        "2026-11-02, -,          ACTIVE,   true",
        "2026-11-03, -,          PENDING,  false",
        "2026-11-03, 2026-11-10, PENDING,  false",
        "2026-10-01, 2026-10-31, EXPIRED,  false",
    })
    @DisplayName("状态和能否检索：失效当天仍有效，生效当天起有效，30 天内到期算即将到期")
    void stateAndCurrent(String from, String to, Validity.State state, boolean current) {
        var validity = new Validity(date(from), date(to));

        assertThat(validity.state(TODAY, 30)).isEqualTo(state);
        assertThat(validity.isCurrent(TODAY)).isEqualTo(current);
    }

    @Test
    @DisplayName("失效日期不能早于生效日期；同一天可以")
    void rejectsReversedRange() {
        assertThatThrownBy(() -> new Validity(TODAY, TODAY.minusDays(1)))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("失效日期不能早于生效日期");
        assertThat(new Validity(TODAY, TODAY).isCurrent(TODAY)).isTrue();
    }

    @Test
    @DisplayName("文档默认长期有效，可以改有效期")
    void documentValidity() {
        var document = Document.received(new com.liming.rag.shared.TenantId(1), 10,
                new UploadedFile("规范.md", "text/markdown", "a".repeat(64), 128), null, Validity.ALWAYS);
        assertThat(document.validity()).isEqualTo(Validity.ALWAYS);

        document.changeValidity(new Validity(null, TODAY));
        assertThat(document.validity().expiresOn()).isEqualTo(TODAY);
    }
}
