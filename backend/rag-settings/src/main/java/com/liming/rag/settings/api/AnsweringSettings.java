package com.liming.rag.settings.api;

import java.time.ZoneId;

/**
 * 问答要用的系统设置（D-045）：行业包启动后不变；拒答话术可以在系统设置里改，每次读最新值；统计时区是部署配置。
 */
public interface AnsweringSettings {

    IndustryPack pack();

    /** 知识库里找不到可靠依据时给员工的回答：界面改过的以界面为准，否则用行业包的。 */
    String refusalMessage();

    /** 使用统计的“一天”按这个时区划分（{@code rag.settings.stats-zone}）。 */
    ZoneId statsZone();
}
