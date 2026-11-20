package com.liming.rag.knowledge.infrastructure.parsing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ImageTextTest {

    private final OcrStub ocr = new OcrStub();
    private final ImageText images = new ImageText(ocr.url());

    ImageTextTest() throws IOException {
    }

    @AfterEach
    void stop() {
        ocr.close();
    }

    @Test
    @DisplayName("同一行的文字框合成一行，行与行换行；置信度低于 0.5 的框丢掉（#223）")
    void joinsBoxesOnTheSameLine() {
        ocr.respond(200, """
                {"0":{"rec_txt":"第一步：点击","dt_boxes":[[16,16],[193,16],[193,54],[16,54]],"score":0.997},
                 "1":{"rec_txt":"“交班”","dt_boxes":[[193,15],[284,15],[284,52],[193,52]],"score":0.999},
                 "2":{"rec_txt":"按钮","dt_boxes":[[297,19],[357,18],[358,51],[297,51]],"score":0.999},
                 "3":{"rec_txt":"噪点","dt_boxes":[[400,20],[420,20],[420,40],[400,40]],"score":0.31},
                 "4":{"rec_txt":"输入备用金金额500元","dt_boxes":[[14,75],[310,74],[311,115],[14,116]],"score":0.999}}
                """);

        assertThat(images.recognize(new byte[] {1})).isEqualTo("第一步：点击 “交班” 按钮\n输入备用金金额500元");
    }

    @Test
    @DisplayName("图片里没有文字时返回空；服务拒收这张图（4xx）只跳过这张")
    void emptyOrRejectedImage() {
        ocr.respond(200, "{}");
        assertThat(images.recognize(new byte[] {1})).isEmpty();

        ocr.respond(413, "{\"detail\":\"too large\"}");
        assertThat(images.recognize(new byte[] {1})).isEmpty();
    }

    @Test
    @DisplayName("服务出错（5xx）或连不上时报不可用；没配置地址时不识别")
    void unavailable() {
        ocr.respond(503, "busy");
        assertThatThrownBy(() -> images.recognize(new byte[] {1})).isInstanceOf(ImageText.UnavailableException.class);

        ocr.close();
        assertThatThrownBy(() -> images.recognize(new byte[] {1})).isInstanceOf(ImageText.UnavailableException.class);

        assertThat(images.enabled()).isTrue();
        assertThat(new ImageText("").enabled()).isFalse();
    }
}
