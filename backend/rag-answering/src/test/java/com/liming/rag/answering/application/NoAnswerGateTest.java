package com.liming.rag.answering.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class NoAnswerGateTest {

    private final List<String> out = new ArrayList<>();
    private final AtomicInteger modelDeltas = new AtomicInteger();
    private final NoAnswerGate gate = new NoAnswerGate(out::add, modelDeltas::incrementAndGet);

    @Test
    @DisplayName("模型开头就回 NO_ANSWER（即使分成几段到达）时一个字也不放出，但仍记下模型出字")
    void blocksLeadingMarker() {
        feed("NO_", "ANS", "WER");
        gate.flush();

        assertThat(out).isEmpty();
        assertThat(modelDeltas).hasValue(3);
    }

    @Test
    @DisplayName("确认不是标记后立即放行")
    void opensForNormalAnswers() {
        feed("冷柜", "温度 2–6℃[1]。");
        gate.flush();

        assertThat(String.join("", out)).isEqualTo("冷柜温度 2–6℃[1]。");
    }

    @Test
    @DisplayName("中间或末尾夹带的 NO_ANSWER 被去掉，跨段到达也一样")
    void stripsMarkerInsideAndAtEnd() {
        feed("能答的部分[1]。", "NO_AN", "SWER", "其余资料没有提到。NO_", "ANSWER");
        gate.flush();

        assertThat(String.join("", out)).isEqualTo("能答的部分[1]。其余资料没有提到。");
    }

    @Test
    @DisplayName("末尾像标记开头的几个字先留着，最终没有组成标记时在 flush 时放出")
    void releasesHeldSuffixOnFlush() {
        feed("见第 3 条 NO");
        assertThat(String.join("", out)).isEqualTo("见第 3 条 ");

        gate.flush();

        assertThat(String.join("", out)).isEqualTo("见第 3 条 NO");
    }

    private void feed(String... deltas) {
        for (var delta : deltas) {
            gate.accept(delta);
        }
    }
}
