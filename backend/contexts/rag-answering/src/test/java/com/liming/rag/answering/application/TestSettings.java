package com.liming.rag.answering.application;

import com.liming.rag.settings.api.AnsweringSettings;
import com.liming.rag.settings.api.IndustryPack;
import com.liming.rag.settings.api.StatsZone;
import java.time.ZoneId;
import java.util.List;

/** 测试用的系统设置：新零售行业包的内容，与 industry-packs/retail/pack.yml 一致（IndustryPackFileTest 核对文件本身）。 */
public final class TestSettings implements AnsweringSettings, StatsZone {

    public static final IndustryPack RETAIL_PACK = new IndustryPack("retail", "新零售",
            "你是连锁门店的运营助手，回答门店员工的问题。", "临期商品提前三天下架[2]。",
            "你负责把连锁门店员工的提问改写成便于检索门店制度文件的规范问题。",
            List.of("提到具体商品时，在后面补上它所属的商品类别。"),
            List.of(new IndustryPack.Term("鲜食", List.of("便当", "饭团")),
                    new IndustryPack.Term("报损", List.of("报废", "坏货"))));

    public static final String RETAIL_REFUSAL = "知识库里没有找到能回答这个问题的可靠依据，建议换个问法，或联系店长确认。";

    public static final TestSettings RETAIL = new TestSettings(RETAIL_PACK, RETAIL_REFUSAL);

    private final IndustryPack pack;
    private final String refusal;

    public TestSettings(IndustryPack pack, String refusal) {
        this.pack = pack;
        this.refusal = refusal;
    }

    @Override
    public IndustryPack pack() {
        return pack;
    }

    @Override
    public String refusalMessage() {
        return refusal;
    }

    @Override
    public ZoneId statsZone() {
        return ZoneId.of("Asia/Shanghai");
    }
}
