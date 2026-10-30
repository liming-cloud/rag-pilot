package com.liming.rag.answering.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class StageClockTest {

    @Test
    @DisplayName("没走到的阶段为空；只检索就拒答时只有检索耗时")
    void unreachedStagesAreNull() {
        var clock = new StageClock();
        clock.retrieved();

        var timings = clock.timings();

        assertThat(timings.retrievalMillis()).isNotNull();
        assertThat(timings.rerankMillis()).isNull();
        assertThat(timings.firstTokenMillis()).isNull();
        assertThat(timings.generationMillis()).isNull();
        assertThat(timings.rewriteMillis()).isNull();
    }

    @Test
    @DisplayName("首字只记第一次；生成结束时还没出首字的，首字记为生成结束")
    void firstTokenRecordedOnce() throws InterruptedException {
        var clock = new StageClock();
        clock.retrieved();
        clock.reranked();
        clock.firstToken();
        Thread.sleep(20);
        clock.firstToken();
        clock.generated();

        var timings = clock.timings();

        assertThat(timings.firstTokenMillis()).isLessThan(timings.generationMillis());

        var silent = new StageClock();
        silent.retrieved();
        silent.reranked();
        silent.generated();
        assertThat(silent.timings().firstTokenMillis()).isEqualTo(silent.timings().generationMillis());
    }
}
