package com.liming.rag.answering.application;

import com.liming.rag.answering.api.Answer;

/** 一次问答各阶段结束的时刻；首字只记第一次。评测报告的分段计时来自这里（D-021）。 */
final class StageClock {

    private final long start = System.nanoTime();
    private long retrieved;
    private long reranked;
    private long firstToken;
    private long generated;
    private long rewriteStarted;
    private long rewriteFinished;

    void retrieved() {
        retrieved = System.nanoTime();
    }

    void reranked() {
        reranked = System.nanoTime();
    }

    void rewriteStarted() {
        rewriteStarted = System.nanoTime();
    }

    void rewriteFinished() {
        rewriteFinished = System.nanoTime();
    }

    void firstToken() {
        if (firstToken == 0) {
            firstToken = System.nanoTime();
        }
    }

    void generated() {
        generated = System.nanoTime();
        firstToken();
    }

    Answer.Timings timings() {
        // 生成从重排结束（有改写时从改写结束）开始算。
        long generationStart = rewriteFinished == 0 ? reranked : rewriteFinished;
        return new Answer.Timings(millis(start, retrieved), reranked == 0 ? null : millis(retrieved, reranked),
                firstToken == 0 ? null : millis(generationStart, firstToken),
                generated == 0 ? null : millis(generationStart, generated),
                rewriteFinished == 0 ? null : millis(rewriteStarted, rewriteFinished));
    }

    private static long millis(long from, long to) {
        return (to - from) / 1_000_000;
    }
}
