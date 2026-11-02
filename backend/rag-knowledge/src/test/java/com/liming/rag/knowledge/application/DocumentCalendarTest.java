package com.liming.rag.knowledge.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.liming.rag.knowledge.domain.Validity;
import com.liming.rag.settings.api.AnsweringSettings;
import com.liming.rag.settings.api.IndustryPack;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** “今天”按部署的统计时区算（D-046）：UTC 还是前一天时，北京时间已经是第二天。 */
class DocumentCalendarTest {

    private static DocumentCalendar calendar(String instant, String zone) {
        AnsweringSettings settings = new AnsweringSettings() {
            @Override
            public IndustryPack pack() {
                throw new UnsupportedOperationException();
            }

            @Override
            public String refusalMessage() {
                throw new UnsupportedOperationException();
            }

            @Override
            public ZoneId statsZone() {
                return ZoneId.of(zone);
            }
        };
        var properties = new KnowledgeProperties(new KnowledgeProperties.Ingestion(2),
                new KnowledgeProperties.Storage("./data"), 30);
        return new DocumentCalendar(settings, properties, Clock.fixed(Instant.parse(instant), ZoneOffset.UTC));
    }

    @Test
    @DisplayName("UTC 11 月 1 日 16:30 在北京时间是 11 月 2 日：11 月 1 日失效的文档已失效，11 月 2 日失效的当天仍有效")
    void usesStatsZone() {
        var beijing = calendar("2026-11-01T16:30:00Z", "Asia/Shanghai");
        assertThat(beijing.today()).isEqualTo(LocalDate.of(2026, 11, 2));
        assertThat(beijing.state(new Validity(null, LocalDate.of(2026, 11, 1)))).isEqualTo(Validity.State.EXPIRED);
        assertThat(beijing.state(new Validity(null, LocalDate.of(2026, 11, 2)))).isEqualTo(Validity.State.EXPIRING);

        var utc = calendar("2026-11-01T16:30:00Z", "UTC");
        assertThat(utc.today()).isEqualTo(LocalDate.of(2026, 11, 1));
        assertThat(utc.state(new Validity(null, LocalDate.of(2026, 11, 1)))).isEqualTo(Validity.State.EXPIRING);
    }

    @Test
    @DisplayName("即将到期的窗口：今天到今天加提醒天数（含）")
    void window() {
        var window = calendar("2026-11-01T16:30:00Z", "Asia/Shanghai").window();
        assertThat(window.today()).isEqualTo(LocalDate.of(2026, 11, 2));
        assertThat(window.warnUntil()).isEqualTo(LocalDate.of(2026, 12, 2));
    }
}
