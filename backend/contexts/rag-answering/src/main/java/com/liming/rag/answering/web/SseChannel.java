package com.liming.rag.answering.web;

import com.liming.rag.shared.BusinessException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 流式问答发往浏览器的事件（#136：两个流式接口共用）。事件数据都是 JSON；出错时发 {@code error}（{"code", "detail"}，
 * code 与 HTTP 接口的错误码相同）。员工断开连接后怎么办由调用方决定：直接问答停止生成，会话提问照样生成完并保存（D-036）。
 */
final class SseChannel {

    private static final long TIMEOUT_MILLIS = Duration.ofMinutes(2).toMillis();

    /** 员工断开连接后：停止生成，还是不再发送、照样做完。 */
    enum OnDisconnect {
        STOP, CONTINUE
    }

    /** 后台线程里要做的事，期间用 {@link #send} 推事件，最后的结果也由它推出去。 */
    @FunctionalInterface
    interface Work {
        void run(SseChannel channel);
    }

    private final SseEmitter emitter = new SseEmitter(TIMEOUT_MILLIS);
    private final OnDisconnect onDisconnect;
    private final AtomicBoolean gone = new AtomicBoolean();

    SseChannel(OnDisconnect onDisconnect) {
        this.onDisconnect = onDisconnect;
        emitter.onError(error -> gone.set(true));
        emitter.onTimeout(() -> gone.set(true));
    }

    SseEmitter emitter() {
        return emitter;
    }

    /**
     * 推一个事件。员工已断开时：{@link OnDisconnect#CONTINUE} 什么也不做；{@link OnDisconnect#STOP} 抛
     * {@link UncheckedIOException}，让生成停下来。
     */
    void send(String event, Object data) {
        if (gone.get()) {
            disconnected(null);
            return;
        }
        try {
            emitter.send(SseEmitter.event().name(event).data(data, MediaType.APPLICATION_JSON));
        } catch (IOException | IllegalStateException e) {
            gone.set(true);
            disconnected(e);
        }
    }

    /**
     * 在后台线程里做完 {@code work} 并结束事件流。业务异常发 {@code error} 事件；意外错误记日志、发通用的
     * {@code error} 事件——后台线程里的异常不经过全局异常处理器，不能让连接一直挂到超时。
     */
    void run(Work work, Logger log, String failure) {
        try {
            work.run(this);
            complete();
        } catch (BusinessException e) {
            fail(e.code(), e.getMessage());
        } catch (UncheckedIOException e) {
            // 员工断开连接，已经停止生成。
            emitter.completeWithError(e);
        } catch (RuntimeException e) {
            log.error(failure, e);
            fail("INTERNAL_ERROR", "回答时出错，请稍后再试");
        }
    }

    private void fail(String code, String detail) {
        send("error", Map.of("code", code, "detail", detail));
        complete();
    }

    private void complete() {
        if (!gone.get()) {
            emitter.complete();
        }
    }

    private void disconnected(Exception cause) {
        if (onDisconnect == OnDisconnect.STOP) {
            throw new UncheckedIOException(cause instanceof IOException io ? io : new IOException("员工已断开连接", cause));
        }
    }
}
