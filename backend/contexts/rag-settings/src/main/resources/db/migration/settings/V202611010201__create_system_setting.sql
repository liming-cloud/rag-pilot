-- 所属模块：rag-settings。
-- 目的：系统设置（D-045）：在界面保存过的品牌、问答页文案、推荐问题、拒答话术。没有行的项用部署配置、行业包或
--       内核默认值。部署级，不分租户。推荐问题按顺序一行一个（每个问题不能换行）。
-- 影响：新建表，不改动已有数据；没有任何行时行为与之前完全一致。
-- 回滚：可以删除这张表，所有项退回默认值。
CREATE TABLE system_setting (
    setting_key     varchar(40) PRIMARY KEY CHECK (setting_key IN ('BRAND_NAME', 'BRAND_TAGLINE', 'LOGIN_HINT',
                        'CHAT_PLACEHOLDER', 'CHAT_EMPTY_HINT', 'SUGGESTED_QUESTIONS', 'REFUSAL_MESSAGE')),
    value           text        NOT NULL,
    updated_by      bigint      NOT NULL,
    updated_by_name varchar(50) NOT NULL,
    updated_at      timestamptz NOT NULL DEFAULT now()
);
