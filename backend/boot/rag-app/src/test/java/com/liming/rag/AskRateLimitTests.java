package com.liming.rag;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

/** 提问频率上限（#145 B5）：超过时返回 429、不留下这一轮，也不调用模型。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "rag.answering.limits.per-minute=2", "rag.answering.limits.per-day=10"})
@AutoConfigureTestRestTemplate
class AskRateLimitTests {

    @Autowired
    private TestRestTemplate http;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("一分钟内第三个问题返回 429，说明原因，不留下这一轮")
    void rejectsQuestionsOverTheLimit() {
        TestLogin.asAdmin(http);
        http.postForEntity("/api/knowledge-bases", Map.of("name", "限流测试库"), Map.class);
        long conversation = TestAsk.conversation(http, null, null);
        TestAsk.answer(TestAsk.stream(http, null, conversation, "第一个问题"));
        TestAsk.answer(TestAsk.stream(http, null, conversation, "第二个问题"));
        int turns = jdbc.queryForObject("select count(*) from conversation_turn", Integer.class);

        var headers = new HttpHeaders();
        headers.setAccept(List.of(MediaType.TEXT_EVENT_STREAM, MediaType.APPLICATION_JSON));
        headers.setContentType(MediaType.APPLICATION_JSON);
        var third = http.exchange("/api/conversations/{id}/turns/stream", HttpMethod.POST,
                new HttpEntity<>(Map.of("question", "第三个问题"), headers), String.class, conversation);

        assertThat(third.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(third.getBody()).contains("ANSWERING_RATE_LIMITED").contains("太频繁");
        assertThat(jdbc.queryForObject("select count(*) from conversation_turn", Integer.class)).isEqualTo(turns);
    }
}
