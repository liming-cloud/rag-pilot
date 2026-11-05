package com.liming.rag;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * 集成测试里“问一个问题”（#145：问答只有会话接口，员工用的就是它）：新建会话，流式问一次，取最后 done 事件里这一轮的回答。
 * {@code token} 为 null 时沿用客户端默认的令牌。
 */
final class TestAsk {

    private TestAsk() {
    }

    /** 新建会话；{@code knowledgeBaseIds} 为 null 时选中全部已授权的库。返回会话 id。 */
    static long conversation(TestRestTemplate http, String token, List<Long> knowledgeBaseIds) {
        var headers = new HttpHeaders();
        if (token != null) {
            headers.setBearerAuth(token);
        }
        var body = new HashMap<String, Object>();
        if (knowledgeBaseIds != null) {
            body.put("knowledgeBaseIds", knowledgeBaseIds);
        }
        var created = http.exchange("/api/conversations", HttpMethod.POST, new HttpEntity<>(body, headers), Map.class);
        assertThat(created.getStatusCode()).as("新建会话：" + created.getBody()).isEqualTo(HttpStatus.CREATED);
        return ((Number) created.getBody().get("id")).longValue();
    }

    /** 在会话里流式问一次，返回全部事件。 */
    static List<SseEvents.Event> stream(TestRestTemplate http, String token, long conversationId, String question) {
        return SseEvents.post(http, "/api/conversations/" + conversationId + "/turns/stream",
                Map.of("question", question), token);
    }

    /**
     * 试着问一次：能建会话就问，返回这一轮的回答；一个可用的知识库都没有时会话建不起来（400 {@code ANSWERING_INVALID}），
     * 返回错误的问题详情（带 {@code code}）。越权类测试用它确认“问不到”。
     */
    @SuppressWarnings("unchecked")
    static Map<String, Object> attempt(TestRestTemplate http, String token, List<Long> knowledgeBaseIds,
            String question) {
        var headers = new HttpHeaders();
        if (token != null) {
            headers.setBearerAuth(token);
        }
        var body = new HashMap<String, Object>();
        if (knowledgeBaseIds != null) {
            body.put("knowledgeBaseIds", knowledgeBaseIds);
        }
        var created = http.exchange("/api/conversations", HttpMethod.POST, new HttpEntity<>(body, headers), Map.class);
        if (created.getStatusCode() == HttpStatus.BAD_REQUEST) {
            assertThat(created.getBody()).containsEntry("code", "ANSWERING_INVALID");
            return created.getBody();
        }
        assertThat(created.getStatusCode()).as("新建会话：" + created.getBody()).isEqualTo(HttpStatus.CREATED);
        return answer(stream(http, token, ((Number) created.getBody().get("id")).longValue(), question));
    }

    /** 新建会话问一次，返回这一轮的回答（id、text、refused、refusalReason、citations、topRelevance 等）。 */
    static Map<String, Object> ask(TestRestTemplate http, String token, List<Long> knowledgeBaseIds, String question) {
        return answer(stream(http, token, conversation(http, token, knowledgeBaseIds), question));
    }

    /** 事件流最后的 done 里这一轮的回答；这一轮没有回答（出错、停止）时断言失败。 */
    @SuppressWarnings("unchecked")
    static Map<String, Object> answer(List<SseEvents.Event> events) {
        assertThat(events).as("提问应当有事件").isNotEmpty();
        var done = events.getLast();
        assertThat(done.name()).as("最后应是 done：" + done.data()).isEqualTo("done");
        Map<String, Object> turn = JsonMapper.builder().build().readValue(done.data(), new TypeReference<>() {
        });
        assertThat(turn.get("answer")).as("这一轮应有回答：" + done.data()).isNotNull();
        return (Map<String, Object>) turn.get("answer");
    }
}
