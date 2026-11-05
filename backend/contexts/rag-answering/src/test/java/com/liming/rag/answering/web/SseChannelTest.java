package com.liming.rag.answering.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.liming.rag.shared.BusinessException;
import com.liming.rag.shared.ErrorKind;
import java.io.IOException;
import java.io.Serial;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** 流式事件的失败路径（#145 C8）：员工断开、超时、业务异常、意外错误。 */
class SseChannelTest {

    private static final org.slf4j.Logger LOG = LoggerFactory.getLogger(SseChannelTest.class);

    /** 记下发出的事件名和结束方式；可以让发送失败（员工断开）或触发超时。 */
    private static final class RecordingEmitter extends SseEmitter {

        final List<String> events = new ArrayList<>();
        boolean completed;
        boolean failOnSend;
        Runnable timeout;

        @Override
        public void send(SseEventBuilder builder) throws IOException {
            if (failOnSend) {
                throw new IOException("Broken pipe");
            }
            var text = builder.build().stream().map(ResponseBodyEmitter.DataWithMediaType::getData)
                    .map(Object::toString).reduce("", String::concat);
            events.add(text.substring(text.indexOf("event:") + 6, text.indexOf('\n', text.indexOf("event:"))));
        }

        @Override
        public synchronized void complete() {
            completed = true;
        }

        @Override
        public synchronized void onTimeout(Runnable callback) {
            this.timeout = callback;
        }
    }

    private static final class Failed extends BusinessException {

        @Serial
        private static final long serialVersionUID = 1L;

        Failed() {
            super(ErrorKind.INVALID, "ANSWERING_INVALID", "问题不能为空");
        }
    }

    private final RecordingEmitter emitter = new RecordingEmitter();
    private final SseChannel channel = new SseChannel(emitter);

    @Test
    @DisplayName("正常做完：按顺序发出事件并结束")
    void completesAfterWork() {
        channel.run(events -> {
            events.send("delta", java.util.Map.of("text", "每周二"));
            events.send("done", java.util.Map.of("id", 1));
        }, LOG, "出错");

        assertThat(emitter.events).containsExactly("delta", "done");
        assertThat(emitter.completed).isTrue();
    }

    @Test
    @DisplayName("员工断开后不再发送，但生成照样做完（D-036），不抛异常，也不再结束已断开的连接")
    void keepsWorkingAfterDisconnect() {
        var reached = new ArrayList<String>();
        channel.run(events -> {
            emitter.failOnSend = true;
            events.send("delta", java.util.Map.of("text", "每周二"));
            reached.add("断开之后");
            emitter.failOnSend = false;
            events.send("done", java.util.Map.of("id", 1));
            reached.add("做完");
        }, LOG, "出错");

        assertThat(reached).containsExactly("断开之后", "做完");
        assertThat(emitter.events).as("断开后的事件不再发").isEmpty();
        assertThat(emitter.completed).isFalse();
    }

    @Test
    @DisplayName("超时后不再发送")
    void stopsSendingAfterTimeout() {
        emitter.timeout.run();

        channel.run(events -> events.send("done", java.util.Map.of("id", 1)), LOG, "出错");

        assertThat(emitter.events).isEmpty();
        assertThat(emitter.completed).isFalse();
    }

    @Test
    @DisplayName("业务异常发 error 事件（带错误码）并结束；意外错误发通用的 INTERNAL_ERROR，不把内部信息带给员工")
    void reportsFailures() {
        channel.run(events -> {
            throw new Failed();
        }, LOG, "出错");
        assertThat(emitter.events).containsExactly("error");
        assertThat(emitter.completed).isTrue();

        var other = new RecordingEmitter();
        new SseChannel(other).run(events -> {
            throw new IllegalStateException("数据库连接串 jdbc:postgresql://secret");
        }, LOG, "会话提问出错");
        assertThat(other.events).containsExactly("error");
        assertThat(other.completed).isTrue();
    }
}
