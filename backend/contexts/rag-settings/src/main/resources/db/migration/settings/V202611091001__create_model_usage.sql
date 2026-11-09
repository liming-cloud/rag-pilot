-- 所属模块：rag-settings。
-- 目的：模型用量与费用统计（#169、D-056）：按日 × 用途 × 服务商 × 模型聚合的调用次数和 token 数，加管理员手填的每百万 token 单价。
--       只存聚合数字，不存每次调用的明细，不记问题原文和提问的人；用量保留 365 天，由定时任务清理。部署级，不分租户。
-- 影响：新建两张表，不改动已有数据。
-- 回滚：可以删除这两张表，管理端的“用量”标签页变空。
CREATE TABLE model_usage_daily (
    day           date         NOT NULL,
    -- ANSWER、HEDGE、LIGHT、RERANK，或入库向量化的 EMBEDDING。
    purpose       varchar(20)  NOT NULL,
    -- 服务商地址的域名（带端口时含端口），改名不影响历史。
    provider      varchar(200) NOT NULL,
    model         varchar(200) NOT NULL,
    calls         bigint       NOT NULL,
    input_tokens  bigint       NOT NULL,
    output_tokens bigint       NOT NULL,
    PRIMARY KEY (day, purpose, provider, model)
);

CREATE TABLE model_price (
    model              varchar(200)   PRIMARY KEY,
    -- 每百万 token 的价格（人民币元）。
    input_per_million  numeric(12, 4) NOT NULL CHECK (input_per_million >= 0),
    output_per_million numeric(12, 4) NOT NULL CHECK (output_per_million >= 0),
    updated_at         timestamptz    NOT NULL
);
