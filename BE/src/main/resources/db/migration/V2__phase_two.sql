-- SkyLane 第二阶段 schema (Flyway V2)
-- 设计说明见 docs/design.md 第 8 节。只做增量：新增表和可空 / 有默认值的列，不改 V1 已有的列。

-- ---------- 运营方与角色（8.1） ----------

CREATE TABLE operator (
    id         uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    name       text        NOT NULL UNIQUE,
    created_at timestamptz NOT NULL DEFAULT now()
);

ALTER TABLE app_user ADD COLUMN role text NOT NULL DEFAULT 'customer'
    CHECK (role IN ('customer', 'operator', 'admin'));
ALTER TABLE app_user ADD COLUMN operator_id uuid REFERENCES operator (id);
-- operator 必须属于一家公司，其他角色不能有公司
ALTER TABLE app_user ADD CONSTRAINT app_user_operator_chk
    CHECK ((role = 'operator') = (operator_id IS NOT NULL));

ALTER TABLE device ADD COLUMN operator_id uuid REFERENCES operator (id);  -- 空 = 平台自营
CREATE INDEX device_operator_idx ON device (operator_id);

-- ---------- 预约下单（8.4） ----------

ALTER TABLE quote ADD COLUMN pickup_after timestamptz;                   -- 空 = 现在

-- ---------- 推荐与 Agent（8.2） ----------

CREATE TABLE recommendation (
    id         uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    quote_id   uuid        NOT NULL UNIQUE REFERENCES quote (id),
    option_id  uuid        NOT NULL,
    reason     text        NOT NULL,
    factors    jsonb       NOT NULL,
    model      text,                                                     -- 空 = 只用了规则
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT recommendation_option_fk
        FOREIGN KEY (quote_id, option_id) REFERENCES quote_option (quote_id, id)
);

CREATE TABLE conversation (
    id         uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id    uuid        NOT NULL REFERENCES app_user (id),
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX conversation_user_idx ON conversation (user_id, created_at DESC);

CREATE TABLE conversation_message (
    id              bigserial   PRIMARY KEY,
    conversation_id uuid        NOT NULL REFERENCES conversation (id),
    role            text        NOT NULL CHECK (role IN ('user', 'assistant', 'tool')),
    content         jsonb       NOT NULL,
    input_tokens    integer,
    output_tokens   integer,
    created_at      timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX conversation_message_conv_idx ON conversation_message (conversation_id, id);

CREATE TABLE agent_action (
    id              uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    conversation_id uuid        NOT NULL REFERENCES conversation (id),
    kind            text        NOT NULL CHECK (kind IN ('place_order', 'cancel_order')),
    payload         jsonb       NOT NULL,
    status          text        NOT NULL DEFAULT 'pending'
                                CHECK (status IN ('pending', 'confirmed', 'rejected', 'expired', 'failed')),
    result          jsonb,
    expires_at      timestamptz NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX agent_action_conv_idx ON agent_action (conversation_id, created_at);

-- ---------- 第二阶段查询用的索引 ----------

-- 运营方后台按设备查订单（/ops/orders、/ops/metrics，D29）
CREATE INDEX order_device_created_idx ON delivery_order (device_id, created_at DESC);
-- 后台指标按时间范围统计
CREATE INDEX order_created_idx ON delivery_order (created_at);
-- 按时间顺序找设备空档（D42）、设备排班页（/ops/devices/{id}/schedule）
CREATE INDEX device_block_active_idx ON device_block (device_id, starts_at) WHERE released_at IS NULL;
-- 定时把过期的待确认操作标成 expired（D36）
CREATE INDEX agent_action_pending_idx ON agent_action (expires_at) WHERE status = 'pending';
