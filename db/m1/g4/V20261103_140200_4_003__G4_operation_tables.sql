-- =============================================================================
-- G4 空间资源：运行支撑表（operation / outbox / inbox / idempotency / creation_intent）
--
-- 依据：技术文档 v2.0 §3.1（每组独占 gN_operation 等）、§4.1（幂等与创建意图）、
--       §7.4（后台恢复、错误分类与收敛）、§8.1（事件信封）
--
-- 所有权：这五张表只由 G4 写。不得与他人共享一张可随意写的 outbox。
-- 状态字面量与 g4.yaml 的 OperationState / Phase / OperationResult 保持一致。
-- =============================================================================


-- -----------------------------------------------------------------------------
-- g4_operation 操作记录
--
-- 每个已受理的写命令一条，承载：重试租约、错误分类、恢复入口与结果。
-- status_url 是持久化的本域公开查询路径，不能只返回进度动画地址。
-- -----------------------------------------------------------------------------
CREATE TABLE g4_operation (
    id              uuid        NOT NULL,
    version         bigint      NOT NULL,
    owner_group     varchar(2)  NOT NULL,
    source_type     varchar(10) NOT NULL,
    source_id       uuid        NOT NULL,
    room_id         uuid        NULL,
    scope_kind      varchar(10) NOT NULL,
    category        varchar(24) NOT NULL,
    event_id        uuid        NULL,
    actor_id        uuid        NULL,
    operation_id    uuid        NOT NULL,
    status          varchar(12) NOT NULL,
    phase           varchar(12) NOT NULL,
    request_digest  char(64)    NOT NULL,
    accepted_at     timestamptz NOT NULL,
    attempt_count   integer     NOT NULL DEFAULT 0,
    next_retry_at   timestamptz NULL,
    error_code      varchar(64) NULL,
    error_body      jsonb       NULL,
    needs_attention boolean     NOT NULL DEFAULT false,
    result_outcome  varchar(20) NULL,
    result_ref_type varchar(10) NULL,
    result_ref_id   uuid        NULL,
    result_version  bigint      NULL,
    effective_at    timestamptz NULL,
    status_url      varchar(300) NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT g4_operation_pk PRIMARY KEY (id),
    CONSTRAINT g4_operation_operation_uk UNIQUE (operation_id),
    CONSTRAINT g4_operation_owner_ck CHECK (owner_group = 'G4'),
    CONSTRAINT g4_operation_version_ck CHECK (version > 0),
    CONSTRAINT g4_operation_scope_kind_ck CHECK (scope_kind IN ('ROOM', 'SYSTEM')),
    CONSTRAINT g4_operation_category_ck
        CHECK (category IN ('RESOURCE', 'NOTIFICATION_ROUTE', 'OTHER')),
    CONSTRAINT g4_operation_status_ck
        CHECK (status IN ('PENDING', 'RUNNING', 'RETRY_WAIT', 'SUCCEEDED', 'BLOCKED')),
    CONSTRAINT g4_operation_phase_ck
        CHECK (phase IN ('NONE', 'ACQUIRING', 'VERIFYING', 'RELEASING', 'STARTING', 'ACCEPTING')),
    CONSTRAINT g4_operation_attempt_ck CHECK (attempt_count >= 0),
    CONSTRAINT g4_operation_outcome_ck
        CHECK (result_outcome IS NULL
               OR result_outcome IN ('APPLIED', 'ALREADY_APPLIED', 'TERMINAL', 'EXPIRED', 'REJECTED')),
    -- roomId 非空时必须 ROOM 范围；SYSTEM 范围不得挂房间
    CONSTRAINT g4_operation_scope_match_ck CHECK (
        (room_id IS NOT NULL AND scope_kind = 'ROOM')
        OR (room_id IS NULL AND scope_kind = 'SYSTEM')
    ),
    -- 仅 NOTIFICATION_ROUTE 类别允许非空 event_id（§5.6）
    CONSTRAINT g4_operation_event_ck
        CHECK ((category = 'NOTIFICATION_ROUTE') = (event_id IS NOT NULL))
);

COMMENT ON TABLE g4_operation IS 'G4 独占的操作记录，支撑异常中心查询与受控重试。';
COMMENT ON COLUMN g4_operation.status_url IS '持久化的本域公开查询路径，非进度动画地址。';

CREATE INDEX g4_operation_status_idx ON g4_operation (status, next_retry_at);
CREATE INDEX g4_operation_room_created_idx ON g4_operation (room_id, created_at DESC);
CREATE INDEX g4_operation_source_idx ON g4_operation (source_type, source_id);
CREATE INDEX g4_operation_attention_idx ON g4_operation (needs_attention) WHERE needs_attention;


-- -----------------------------------------------------------------------------
-- g4_outbox 事件发件箱
--
-- 与业务变化同事务写入；worker 通过短租约领取后发送。
-- payload 为 EventEnvelope 的完整 JSON（含 audit），便于重放时不依赖其他表。
-- -----------------------------------------------------------------------------
CREATE TABLE g4_outbox (
    id                uuid        NOT NULL,
    event_id          uuid        NOT NULL,
    event_type        varchar(80) NOT NULL,
    schema_version    smallint    NOT NULL DEFAULT 1,
    producer          varchar(2)  NOT NULL,
    aggregate_type    varchar(10) NOT NULL,
    aggregate_id      uuid        NOT NULL,
    aggregate_version bigint      NOT NULL,
    room_id           uuid        NULL,
    occurred_at       timestamptz NOT NULL,
    actor_id          uuid        NULL,
    actor_kind        varchar(8)  NOT NULL,
    trace_id          uuid        NOT NULL,
    operation_id      uuid        NULL,
    payload           jsonb       NOT NULL,
    attempt_count     integer     NOT NULL DEFAULT 0,
    next_retry_at     timestamptz NULL,
    delivered_at      timestamptz NULL,
    created_at        timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT g4_outbox_pk PRIMARY KEY (id),
    CONSTRAINT g4_outbox_event_uk UNIQUE (event_id),
    CONSTRAINT g4_outbox_producer_ck CHECK (producer = 'G4'),
    CONSTRAINT g4_outbox_schema_version_ck CHECK (schema_version = 1),
    CONSTRAINT g4_outbox_actor_kind_ck CHECK (actor_kind IN ('USER', 'SYSTEM')),
    CONSTRAINT g4_outbox_aggregate_version_ck CHECK (aggregate_version > 0),
    CONSTRAINT g4_outbox_attempt_ck CHECK (attempt_count >= 0)
);

COMMENT ON TABLE g4_outbox IS 'G4 事件发件箱，与业务写同事务提交；未确认送达前不得删除。';

CREATE INDEX g4_outbox_pending_idx ON g4_outbox (next_retry_at) WHERE delivered_at IS NULL;
CREATE INDEX g4_outbox_room_idx ON g4_outbox (room_id, occurred_at);


-- -----------------------------------------------------------------------------
-- g4_inbox 事件收件箱（幂等）
--
-- C-E 收件确认只表示事件已持久化，不表示已处理完成。
-- -----------------------------------------------------------------------------
CREATE TABLE g4_inbox (
    event_id       uuid        NOT NULL,
    event_type     varchar(80) NOT NULL,
    schema_version smallint    NOT NULL,
    producer       varchar(2)  NOT NULL,
    received_at    timestamptz NOT NULL DEFAULT now(),
    processed_at   timestamptz NULL,

    CONSTRAINT g4_inbox_pk PRIMARY KEY (event_id),
    CONSTRAINT g4_inbox_producer_ck CHECK (producer IN ('G1', 'G2', 'G3', 'G4', 'G5', 'G6')),
    CONSTRAINT g4_inbox_schema_version_ck CHECK (schema_version >= 1)
);

COMMENT ON TABLE g4_inbox IS '事件去重收件箱；收件成功不等于业务处理完成。';

CREATE INDEX g4_inbox_unprocessed_idx ON g4_inbox (received_at) WHERE processed_at IS NULL;


-- -----------------------------------------------------------------------------
-- g4_idempotency 对外写请求幂等
--
-- 作用域：用户 ID + method + 规范路径（§4.1）。
-- 同键同正文返回已持久结果，异正文 409 IDEMPOTENCY_CONFLICT。
-- 完整响应缓存 24 小时；长期幂等由 g4_creation_intent 承担。
-- -----------------------------------------------------------------------------
CREATE TABLE g4_idempotency (
    id              uuid        NOT NULL,
    scope_key       varchar(300) NOT NULL,
    idem_key        uuid        NOT NULL,
    request_digest  char(64)    NOT NULL,
    response_status integer     NULL,
    response_body   jsonb       NULL,
    replay_header   boolean     NULL,
    created_at      timestamptz NOT NULL DEFAULT now(),
    expires_at      timestamptz NOT NULL,

    CONSTRAINT g4_idempotency_pk PRIMARY KEY (id),
    CONSTRAINT g4_idempotency_unique_uk UNIQUE (scope_key, idem_key),
    CONSTRAINT g4_idempotency_status_ck
        CHECK (response_status IS NULL OR response_status BETWEEN 100 AND 599)
);

COMMENT ON TABLE g4_idempotency IS '对外写请求的幂等记录；异正文同键返回 409。';

CREATE INDEX g4_idempotency_expiry_idx ON g4_idempotency (expires_at);


-- -----------------------------------------------------------------------------
-- g4_creation_intent 创建意图长期索引
--
-- 创建类接口在 owner 事务内保存唯一索引：
-- 用户 ID + method + 规范路径 + 原幂等键 -> 请求 HMAC 摘要、原对象 ID。
-- 响应缓存过期后仍先查本表：同摘要返回原对象当前记录并标记 Replayed，异摘要 409。
-- 保留期与关联业务记录一致，不随缓存过期删除。
-- -----------------------------------------------------------------------------
CREATE TABLE g4_creation_intent (
    id             uuid         NOT NULL,
    actor_id       uuid         NOT NULL,
    method         varchar(10)  NOT NULL,
    canonical_path varchar(300) NOT NULL,
    idem_key       uuid         NOT NULL,
    request_digest char(64)     NOT NULL,
    object_id      uuid         NOT NULL,
    created_at     timestamptz  NOT NULL DEFAULT now(),

    CONSTRAINT g4_creation_intent_pk PRIMARY KEY (id),
    CONSTRAINT g4_creation_intent_unique_uk
        UNIQUE (actor_id, method, canonical_path, idem_key),
    CONSTRAINT g4_creation_intent_method_ck
        CHECK (method IN ('POST', 'PUT', 'PATCH', 'DELETE'))
);

COMMENT ON TABLE g4_creation_intent IS '创建类接口的长期幂等索引，缓存过期后仍可返回原对象。';
