package com.liming.rag.answering.application.insight;

import static com.liming.rag.answering.application.InMemoryAnswering.TENANT;
import static org.assertj.core.api.Assertions.assertThat;

import com.liming.rag.answering.application.AnswerRecorded;
import com.liming.rag.answering.application.InMemoryAnswering;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 记录回答后在后台算问题向量（#145 C6），知识缺口归类时直接用。 */
class QuestionVectorRecorderTest {

    private final InMemoryAnswering fixture = new InMemoryAnswering();
    private final QuestionVectorRecorder recorder = new QuestionVectorRecorder(fixture.embeddings, fixture.gaps,
            Runnable::run);

    @Test
    @DisplayName("回答记下后算出问题向量并存进这条记录")
    void storesQuestionVector() {
        fixture.embeddings.topic("冷柜", 1, 0, 0);

        recorder.on(new AnswerRecorded(TENANT, 42, "冷柜温度要多少？"));

        assertThat(fixture.embeddings.embedded).containsExactly("冷柜温度要多少？");
        assertThat(fixture.gaps.savedVectors).containsOnlyKeys(42L);
        assertThat(fixture.gaps.savedVectors.get(42L)).containsExactly(1, 0, 0);
    }

    @Test
    @DisplayName("嵌入模型不可用时不存、不抛出，回答不受影响；知识缺口归类时再补算")
    void skipsWhenModelUnavailable() {
        fixture.embeddings.unavailable = true;

        recorder.on(new AnswerRecorded(TENANT, 42, "冷柜温度要多少？"));

        assertThat(fixture.gaps.savedVectors).isEmpty();
    }
}
