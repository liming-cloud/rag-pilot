package com.liming.rag.answering.application.review;

import static com.liming.rag.answering.application.InMemoryAnswering.TENANT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.liming.rag.answering.application.AnsweringException;
import com.liming.rag.answering.application.InMemoryAnswering;
import com.liming.rag.answering.application.TestSettings;
import com.liming.rag.answering.application.ask.AskResult;
import com.liming.rag.answering.domain.Citation;
import com.liming.rag.shared.UserId;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 点开引用（#145 A11）：只记哪条回答的第几个引用和时间；只有提问人能报，引用位置要存在。 */
class CitationOpenServiceTest {

    private static final UserId ASKER = new UserId(7);
    private static final UserId OTHER = new UserId(8);
    private static final Instant NOW = Instant.parse("2026-11-05T08:00:00Z");

    private final InMemoryAnswering fixture = new InMemoryAnswering();
    private final List<String> opened = new ArrayList<>();
    private final CitationOpenService service = new CitationOpenService(fixture.records,
            (answerId, index, at) -> opened.add(answerId + "#" + index + "@" + at),
            Clock.fixed(NOW, ZoneOffset.UTC));
    private final AnswerLogService log = new AnswerLogService(fixture.records, fixture.reviews, fixture.users,
            fixture.access, TestSettings.RETAIL, event -> { }, Clock.systemUTC());

    @Test
    @DisplayName("提问人点开引用：记下回答、第几个引用和时间")
    void recordsOpen() {
        long id = answered();

        service.opened(TENANT, ASKER, id, 2);

        assertThat(opened).containsExactly(id + "#2@" + NOW.atOffset(ZoneOffset.UTC));
    }

    @Test
    @DisplayName("别人的回答、不存在的回答、不存在的引用位置都按不存在处理，什么也不记")
    void rejectsOthersAndUnknown() {
        long id = answered();

        for (Runnable report : List.<Runnable>of(() -> service.opened(TENANT, OTHER, id, 1),
                () -> service.opened(TENANT, ASKER, 404, 1), () -> service.opened(TENANT, ASKER, id, 3),
                () -> service.opened(TENANT, ASKER, id, 0))) {
            assertThatThrownBy(report::run).isInstanceOfSatisfying(AnsweringException.class,
                    e -> assertThat(e.reason()).isEqualTo(AnsweringException.Reason.NOT_FOUND));
        }
        assertThat(opened).isEmpty();
    }

    private long answered() {
        var citations = List.of(new Citation(1, 100, 50, "陈列规范", "陈列规范", null),
                new Citation(2, 101, 51, "临期管理", "临期", null));
        return log.record(TENANT, ASKER, "青柚在哪？", AskResult.answered("A 区[1][2]。", citations, 0.9)).id();
    }
}
