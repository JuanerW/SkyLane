-- SkyLane MVP schema (Flyway V1)
-- 设计说明见 docs/design.md 第 5 节。时间一律 timestamptz（UTC），坐标 geography(Point, 4326)，金额整数美分。

CREATE EXTENSION IF NOT EXISTS postgis;
CREATE EXTENSION IF NOT EXISTS btree_gist;
CREATE EXTENSION IF NOT EXISTS citext;

-- ---------- 账号 ----------

CREATE TABLE app_user (
    id            uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    email         citext      NOT NULL UNIQUE,
    display_name  text        NOT NULL,
    google_sub    text        UNIQUE,                       -- 绑定的 Google 身份，可空
    token_version integer     NOT NULL DEFAULT 0,           -- 改密码 +1，旧 JWT 失效
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now(),
    disabled_at   timestamptz
);

CREATE TABLE user_credential (
    user_id       uuid        PRIMARY KEY REFERENCES app_user (id) ON DELETE CASCADE,
    password_hash text        NOT NULL,                     -- BCrypt
    updated_at    timestamptz NOT NULL DEFAULT now()
);

-- ---------- 运力 ----------

CREATE TABLE station (
    id           uuid                   PRIMARY KEY DEFAULT gen_random_uuid(),
    code         text                   NOT NULL UNIQUE,
    name         text                   NOT NULL,
    address_text text                   NOT NULL,
    location     geography(Point, 4326) NOT NULL,
    is_active    boolean                NOT NULL DEFAULT true,
    created_at   timestamptz            NOT NULL DEFAULT now()
);
CREATE INDEX station_location_gix ON station USING gist (location);

CREATE TABLE device (
    id           uuid                   PRIMARY KEY DEFAULT gen_random_uuid(),
    code         text                   NOT NULL UNIQUE,   -- 如 R-114、DRN-214
    station_id   uuid                   NOT NULL REFERENCES station (id),
    device_type  text                   NOT NULL CHECK (device_type IN ('robot', 'drone')),
    status       text                   NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'offline', 'maintenance')),
    battery_pct  numeric(5, 2)          CHECK (battery_pct BETWEEN 0 AND 100),  -- 仅展示
    location     geography(Point, 4326),
    last_seen_at timestamptz,
    created_at   timestamptz            NOT NULL DEFAULT now()
);
CREATE INDEX device_station_type_idx ON device (station_id, device_type);

-- ---------- 报价 ----------

CREATE TABLE quote (
    id                uuid                   PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id           uuid                   NOT NULL REFERENCES app_user (id),
    item_description  text                   NOT NULL,
    is_fragile        boolean                NOT NULL DEFAULT false,
    weight_kg         numeric(5, 2)          NOT NULL CHECK (weight_kg > 0 AND weight_kg <= 10),
    notes             text                   CHECK (char_length(notes) <= 500),
    pickup_text       text                   NOT NULL,
    pickup_location   geography(Point, 4326) NOT NULL,
    dropoff_text      text                   NOT NULL,
    dropoff_location  geography(Point, 4326) NOT NULL,
    deadline_at       timestamptz,                          -- 空 = ASAP
    expires_at        timestamptz            NOT NULL,
    algorithm_version text,
    created_at        timestamptz            NOT NULL DEFAULT now()
);

CREATE TABLE quote_option (
    id                 uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    quote_id           uuid        NOT NULL REFERENCES quote (id) ON DELETE CASCADE,
    station_id         uuid        NOT NULL REFERENCES station (id),
    device_type        text        NOT NULL CHECK (device_type IN ('robot', 'drone')),
    available          boolean     NOT NULL,
    unavailable_reason text        CHECK (unavailable_reason IN
                           ('OVERWEIGHT', 'STEEP_TERRAIN', 'OUT_OF_RANGE', 'NO_DEVICE_AVAILABLE', 'DEADLINE_MISSED')),
    departs_at         timestamptz,
    eta_at             timestamptz,
    price_cents        integer     CHECK (price_cents >= 0),
    distance_m         integer     CHECK (distance_m >= 0),
    leg1_m             integer     CHECK (leg1_m >= 0),     -- 站点 → 取件
    leg2_m             integer     CHECK (leg2_m >= 0),     -- 取件 → 收件
    route_points       jsonb,                               -- [[lat, lng], ...]
    tags               text[]      NOT NULL DEFAULT '{}',   -- fastest / cheapest / recommended
    created_at         timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT quote_option_quote_uq UNIQUE (quote_id, id),
    CONSTRAINT quote_option_reason_chk CHECK (available OR unavailable_reason IS NOT NULL)
);

-- ---------- 订单 ----------

CREATE TABLE delivery_order (
    id               uuid                   PRIMARY KEY DEFAULT gen_random_uuid(),  -- 即订单号，页面显示前 8 位
    user_id          uuid                   NOT NULL REFERENCES app_user (id),
    quote_id         uuid                   NOT NULL REFERENCES quote (id),
    option_id        uuid                   NOT NULL,
    device_id        uuid                   NOT NULL REFERENCES device (id),
    device_type      text                   NOT NULL CHECK (device_type IN ('robot', 'drone')),
    status           text                   NOT NULL DEFAULT 'confirmed',
    recipient_name   text                   NOT NULL,
    recipient_phone  text                   NOT NULL CHECK (recipient_phone ~ '^\+[1-9][0-9]{7,14}$'),  -- E.164
    price_cents      integer                NOT NULL CHECK (price_cents >= 0),
    notes            text                   CHECK (char_length(notes) <= 500),
    pickup_text      text                   NOT NULL,
    pickup_location  geography(Point, 4326) NOT NULL,
    dropoff_text     text                   NOT NULL,
    dropoff_location geography(Point, 4326) NOT NULL,
    route_points     jsonb                  NOT NULL,
    departs_at       timestamptz            NOT NULL,     -- 此前可取消
    eta_at           timestamptz            NOT NULL,
    idempotency_key  text                   NOT NULL,
    confirmed_at     timestamptz            NOT NULL DEFAULT now(),
    completed_at     timestamptz,
    canceled_at      timestamptz,
    cancel_reason    text,
    created_at       timestamptz            NOT NULL DEFAULT now(),
    updated_at       timestamptz            NOT NULL DEFAULT now(),
    CONSTRAINT order_option_fk FOREIGN KEY (quote_id, option_id) REFERENCES quote_option (quote_id, id),
    CONSTRAINT order_idem_uq UNIQUE (user_id, idempotency_key),
    CONSTRAINT order_status_chk CHECK (status IN
        ('confirmed', 'dispatched', 'picked_up', 'in_transit', 'arrived', 'completed', 'canceled', 'failed'))
);
CREATE INDEX order_user_created_idx ON delivery_order (user_id, created_at DESC);
CREATE INDEX order_status_idx ON delivery_order (status);

CREATE TABLE order_item (
    id          uuid          PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id    uuid          NOT NULL UNIQUE REFERENCES delivery_order (id) ON DELETE CASCADE,  -- MVP 每单一件
    description text          NOT NULL,
    is_fragile  boolean       NOT NULL DEFAULT false,
    weight_kg   numeric(5, 2) NOT NULL CHECK (weight_kg > 0 AND weight_kg <= 10)
);

-- 设备占用：每单写两段（order：出发到送达；return：返航）
CREATE TABLE device_block (
    id          uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    device_id   uuid        NOT NULL REFERENCES device (id),
    order_id    uuid        REFERENCES delivery_order (id),
    kind        text        NOT NULL CHECK (kind IN ('order', 'return', 'charging', 'maintenance')),  -- MVP 只用 order / return
    starts_at   timestamptz NOT NULL,
    ends_at     timestamptz NOT NULL,
    period      tstzrange   GENERATED ALWAYS AS (tstzrange(starts_at, ends_at, '[)')) STORED,
    released_at timestamptz,                                -- 取消时填写
    created_at  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT device_block_time_chk CHECK (ends_at > starts_at),
    CONSTRAINT device_block_order_chk CHECK (kind NOT IN ('order', 'return') OR order_id IS NOT NULL),
    -- 同一设备的有效占用不能重叠：并发下单的最后防线
    CONSTRAINT device_block_no_overlap EXCLUDE USING gist (device_id WITH =, period WITH &&) WHERE (released_at IS NULL)
);
CREATE INDEX device_block_order_idx ON device_block (order_id);

-- 状态时间线；event_id 兼作 SSE 的事件 id
CREATE TABLE order_event (
    event_id    bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    order_id    uuid        NOT NULL REFERENCES delivery_order (id),
    status      text        NOT NULL CHECK (status IN
                    ('confirmed', 'dispatched', 'picked_up', 'in_transit', 'arrived', 'completed', 'canceled', 'failed')),
    occurred_at timestamptz NOT NULL DEFAULT now(),
    actor_type  text        NOT NULL CHECK (actor_type IN ('system', 'device', 'user', 'agent', 'operator')),
    actor_id    uuid,
    details     jsonb,
    request_key text
);
CREATE INDEX order_event_order_idx ON order_event (order_id, event_id);
CREATE UNIQUE INDEX order_event_request_key_uq ON order_event (request_key) WHERE request_key IS NOT NULL;

CREATE TABLE delivery_review (
    id         uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id   uuid        NOT NULL UNIQUE REFERENCES delivery_order (id),
    user_id    uuid        NOT NULL REFERENCES app_user (id),
    rating     smallint    NOT NULL CHECK (rating BETWEEN 1 AND 5),
    comment    text,
    created_at timestamptz NOT NULL DEFAULT now()
);
