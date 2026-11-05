package com.liming.rag.settings.application.model;

/** 可以在管理端单独配置的模型用途（D-043）。嵌入不在其中：换嵌入要重建索引。名字和存库、接口里的取值一致，不能改。 */
public enum ModelPurpose {
    /** 生成回答；低分改写、追问检索词也用它。 */
    ANSWER,
    /** 首字太慢时的备用请求（D-028）。 */
    HEDGE,
    /** 会话标题等轻量任务（D-036）。 */
    LIGHT,
    /** 重排。 */
    RERANK
}
