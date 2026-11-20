package com.liming.rag.knowledge.infrastructure.parsing;

import com.liming.rag.knowledge.application.KnowledgeProperties;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 识别图片里的文字（#223、D-074）：把图片发给宿主机上构建的 RapidOCR 服务（{@code deploy/ocr.Dockerfile}），
 * 同一行的文字框合成一行。没配置 {@code rag.knowledge.ocr.url} 时不识别。
 */
@Component
class ImageText {

    private static final Logger log = LoggerFactory.getLogger(ImageText.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    /** 置信度低于这个的框多是噪点、图标。 */
    private static final double MIN_SCORE = 0.5;
    /** CPU 上识别一张大截图要几秒，留足余量。 */
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(120);

    /** 识别服务连不上、超时或出错：这次读不出图片里的文字，以后再补。 */
    static final class UnavailableException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        UnavailableException(Throwable cause) {
            super("图片文字识别服务不可用", cause);
        }
    }

    private final @Nullable RestClient client;

    @Autowired
    ImageText(KnowledgeProperties properties) {
        this(properties.ocr().url());
    }

    ImageText(String url) {
        if (url.isBlank()) {
            client = null;
            return;
        }
        // 固定 HTTP/1.1：JDK 默认在明文连接上请求升级到 HTTP/2（h2c），uvicorn 不支持，会丢掉请求体。
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5)).build());
        factory.setReadTimeout(READ_TIMEOUT);
        client = RestClient.builder().baseUrl(url).requestFactory(factory).build();
    }

    boolean enabled() {
        return client != null;
    }

    /**
     * @param png PNG 图片
     * @return 识别出的文字，一行一条；没有文字或服务拒收这张图时为空
     * @throws UnavailableException 服务连不上、超时或出错（5xx）
     */
    String recognize(byte[] png) {
        if (client == null) {
            throw new IllegalStateException("没有配置图片文字识别服务");
        }
        var form = new LinkedMultiValueMap<String, Object>();
        form.add("image_file", new ByteArrayResource(png) {
            @Override
            public String getFilename() {
                return "image.png";
            }
        });
        try {
            var body = client.post().uri("/ocr").contentType(MediaType.MULTIPART_FORM_DATA).body(form)
                    .retrieve().body(String.class);
            return body == null ? "" : lines(JSON.readTree(body));
        } catch (HttpClientErrorException e) {
            log.warn("event=ocr_image_rejected status={}", e.getStatusCode().value());
            return "";
        } catch (RestClientException | JacksonException e) {
            throw new UnavailableException(e);
        }
    }

    /** RapidOCR 按阅读顺序返回文字框；框的竖直中心落在上一框的上下边之间就算同一行。 */
    private static String lines(JsonNode boxes) {
        var lines = new ArrayList<String>();
        var line = new StringBuilder();
        double top = Double.NaN;
        double bottom = Double.NaN;
        for (var box : boxes) {
            var text = box.path("rec_txt").asString("").strip();
            if (text.isEmpty() || box.path("score").asDouble(0) < MIN_SCORE) {
                continue;
            }
            var ys = ys(box.path("dt_boxes"));
            double center = (ys.getFirst() + ys.getLast()) / 2;
            if (!line.isEmpty() && center >= top && center <= bottom) {
                line.append(' ').append(text);
            } else {
                if (!line.isEmpty()) {
                    lines.add(line.toString());
                }
                line = new StringBuilder(text);
                top = ys.getFirst();
                bottom = ys.getLast();
            }
        }
        if (!line.isEmpty()) {
            lines.add(line.toString());
        }
        return String.join("\n", lines);
    }

    /** 框四个角的最小、最大纵坐标。 */
    private static List<Double> ys(JsonNode corners) {
        double min = Double.MAX_VALUE;
        double max = -Double.MAX_VALUE;
        for (var corner : corners) {
            double y = corner.path(1).asDouble(0);
            min = Math.min(min, y);
            max = Math.max(max, y);
        }
        return List.of(min, max);
    }
}
