package com.liming.rag.knowledge.application;

import com.liming.rag.knowledge.domain.Validity;
import com.liming.rag.settings.api.AnsweringSettings;
import java.time.Clock;
import java.time.LocalDate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 有效期按哪一天算（D-046）：部署的统计时区（{@code rag.settings.stats-zone}）里的今天，和“即将到期”的提前天数。
 * 检索过滤和管理端显示都从这里取，保证两边对“今天”的理解一致。
 */
@Component
public class DocumentCalendar {

    private final AnsweringSettings settings;
    private final KnowledgeProperties properties;
    private final Clock clock;

    @Autowired
    DocumentCalendar(AnsweringSettings settings, KnowledgeProperties properties) {
        this(settings, properties, Clock.systemUTC());
    }

    DocumentCalendar(AnsweringSettings settings, KnowledgeProperties properties, Clock clock) {
        this.settings = settings;
        this.properties = properties;
        this.clock = clock;
    }

    public LocalDate today() {
        return LocalDate.now(clock.withZone(settings.statsZone()));
    }

    public int warningDays() {
        return properties.expiryWarningDays();
    }

    public Validity.State state(Validity validity) {
        return validity.state(today(), warningDays());
    }

    KnowledgeQueries.Window window() {
        var today = today();
        return new KnowledgeQueries.Window(today, today.plusDays(warningDays()));
    }
}
