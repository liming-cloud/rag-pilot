package com.liming.rag;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;

/**
 * 集成测试读 SSE 的工具：逐行解析事件，并记下每个事件到达时距发出请求的毫秒数，评测用它算首字延迟。
 */
final class SseEvents {

    record Event(String name, String data, long atMillis) {
    }

    private SseEvents() {
    }

    /** POST 一个 JSON 请求并读完整个事件流。{@code token} 为 null 时沿用客户端默认的令牌。 */
    static List<Event> post(TestRestTemplate http, String path, Object body, String token) {
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.TEXT_EVENT_STREAM));
        if (token != null) {
            headers.setBearerAuth(token);
        }
        var restTemplate = http.getRestTemplate();
        long start = System.nanoTime();
        return restTemplate.execute(path, HttpMethod.POST,
                restTemplate.httpEntityCallback(new HttpEntity<>(body, headers)),
                response -> {
                    var events = new ArrayList<Event>();
                    var reader = new BufferedReader(new InputStreamReader(response.getBody(), StandardCharsets.UTF_8));
                    String name = null;
                    var data = new StringBuilder();
                    for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                        if (line.startsWith("event:")) {
                            name = line.substring("event:".length()).strip();
                        } else if (line.startsWith("data:")) {
                            data.append(line.substring("data:".length()));
                        } else if (line.isEmpty() && name != null) {
                            events.add(new Event(name, data.toString(), (System.nanoTime() - start) / 1_000_000));
                            name = null;
                            data.setLength(0);
                        }
                    }
                    return events;
                });
    }
}
