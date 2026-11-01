-- 所属模块：rag-settings。
-- 目的：模型管理（D-043、D-044）：服务商（密钥 AES-GCM 加密存放）、各用途在界面配置的模型、变更记录。
--       模型配置是部署级的，不分租户。某个用途在 model_purpose 里没有行时，用环境变量的配置。
-- 影响：新建表，不改动已有数据；没有任何行时行为与之前完全一致。
-- 回滚：可以删除这三张表，所有用途退回环境变量配置。
CREATE TABLE model_provider (
    id             bigserial PRIMARY KEY,
    name           varchar(30)  NOT NULL,
    base_url       varchar(300) NOT NULL,
    -- 12 字节 IV + AES-GCM 密文；不需要密钥的服务为 null。
    api_key_cipher bytea,
    api_key_last4  varchar(4),
    created_at     timestamptz  NOT NULL DEFAULT now(),
    updated_at     timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT model_provider_name_unique UNIQUE (name)
);

CREATE TABLE model_purpose (
    purpose         varchar(20)  PRIMARY KEY CHECK (purpose IN ('ANSWER', 'HEDGE', 'LIGHT', 'RERANK')),
    -- null 表示用环境变量里的服务商，只换模型名。还在用的服务商不能删。
    provider_id     bigint       REFERENCES model_provider (id),
    model           varchar(200) NOT NULL,
    updated_by_name varchar(50)  NOT NULL,
    updated_at      timestamptz  NOT NULL DEFAULT now()
);

-- 变更记录保存当时的服务商名称，服务商之后改名或删除也看得懂；after_provider_id 用来恢复，null 表示环境变量的服务商。
CREATE TABLE model_change (
    id                   bigserial PRIMARY KEY,
    purpose              varchar(20)  NOT NULL,
    before_provider_name varchar(80),
    before_model         varchar(200),
    after_provider_id    bigint,
    after_provider_name  varchar(80)  NOT NULL,
    after_model          varchar(200) NOT NULL,
    changed_by           bigint       NOT NULL,
    changed_by_name      varchar(50)  NOT NULL,
    changed_at           timestamptz  NOT NULL DEFAULT now(),
    restored_from        bigint
);

CREATE INDEX model_change_recent_idx ON model_change (changed_at DESC, id DESC);
