-- =============================================================================
-- G4 空间资源：占用台账
--
-- 依据：技术文档 v2.0 §3.1（resource_claim / claim_history / source_cursor /
--       reservation_user_lock / resource_impact_preview）、§7.1（锁顺序）、
--       §7.2（可用性、预览与来源版本）、§7.3（截止原子裁决）
--
-- 核心语义：
--   * 四类来源 PERSONAL / WHOLE / REPAIR / ADMIN 各自独立叠加，解除一项不得覆盖其他项。
--   * 终态（RELEASED/INVALIDATED/REJECTED/EXPIRED）记录为墓碑永久保留，不得删除；
--     晚到的 ACTIVE 只能得到 SOURCE_TERMINAL。
--   * ABSENT 只是「查询结果为空」的表达，不落库，因此不在 state 的取值范围内。
--   * source_version 由来源 owner 单调递增；ledger_version 是 G4 账本自己的版本，
--     两者不可混用（Claim.version 与 ClaimResult.resultVersion）。
-- =============================================================================


-- -----------------------------------------------------------------------------
-- resource_claim 占用台账
--
-- seat_id 的取值矩阵由 CHECK resource_claim_scope_ck 强制：
--   PERSONAL          -> seat_id 必须是本室座位（且区间必须有 end）
--   WHOLE / REPAIR    -> seat_id 必须为 NULL，作用整间（含之后新增的座位）
--   ADMIN             -> 可为 NULL（整间停用）或本室座位（单座停用）
-- 复合外键 (seat_id, room_id) -> seat (id, room_id) 保证座位确属该房间。
-- -----------------------------------------------------------------------------
CREATE TABLE resource_claim (
    id             uuid        NOT NULL,
    version        bigint      NOT NULL,
    room_id        uuid        NOT NULL,
    seat_id        uuid        NULL,
    source_type    varchar(10) NOT NULL,
    source_id      uuid        NOT NULL,
    start_at       timestamptz NOT NULL,
    end_at         timestamptz NULL,
    state          varchar(12) NOT NULL,
    effective_at   timestamptz NOT NULL,
    released_at    timestamptz NULL,
    reason         varchar(1000) NOT NULL,
    release_reason varchar(1000) NULL,
    source_version bigint      NOT NULL,
    operation_id   uuid        NOT NULL,
    created_at     timestamptz NOT NULL DEFAULT now(),
    updated_at     timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT resource_claim_pk PRIMARY KEY (id),
    CONSTRAINT resource_claim_source_uk UNIQUE (source_type, source_id),
    CONSTRAINT resource_claim_room_fk FOREIGN KEY (room_id) REFERENCES room (id),
    CONSTRAINT resource_claim_seat_fk FOREIGN KEY (seat_id, room_id) REFERENCES seat (id, room_id),
    CONSTRAINT resource_claim_version_ck CHECK (version > 0),
    CONSTRAINT resource_claim_source_version_ck CHECK (source_version > 0),
    CONSTRAINT resource_claim_source_type_ck
        CHECK (source_type IN ('PERSONAL', 'WHOLE', 'REPAIR', 'ADMIN')),
    CONSTRAINT resource_claim_state_ck
        CHECK (state IN ('ACTIVE', 'RELEASED', 'INVALIDATED', 'REJECTED', 'EXPIRED')),
    CONSTRAINT resource_claim_interval_ck CHECK (end_at IS NULL OR start_at < end_at),
    CONSTRAINT resource_claim_reason_ck CHECK (btrim(reason) <> ''),
    CONSTRAINT resource_claim_scope_ck CHECK (
        (source_type = 'PERSONAL' AND seat_id IS NOT NULL)
        OR (source_type IN ('WHOLE', 'REPAIR') AND seat_id IS NULL)
        OR (source_type = 'ADMIN')
    ),
    -- 个人预约一定有结束时刻；REPAIR 未释放时 end 为 NULL 属正常
    CONSTRAINT resource_claim_personal_end_ck
        CHECK (source_type <> 'PERSONAL' OR end_at IS NOT NULL),
    -- 整室占用来自 G2 的申请快照，区间必有界。缺 end 会把整间永久锁死，属数据错误。
    CONSTRAINT resource_claim_whole_end_ck
        CHECK (source_type <> 'WHOLE' OR end_at IS NOT NULL),
    -- RELEASED 必须有释放时刻，否则无法判断占用何时结束；
    -- 其他终态（INVALIDATED / REJECTED / EXPIRED）由维护或裁决产生，可以没有释放时刻。
    CONSTRAINT resource_claim_release_ck CHECK (
        CASE
            WHEN state = 'RELEASED' THEN released_at IS NOT NULL AND released_at >= effective_at
            ELSE released_at IS NULL OR released_at >= effective_at
        END
    )
);

COMMENT ON TABLE resource_claim IS 'G4 占用台账。四类来源独立叠加，终态为不可删除的墓碑。';
COMMENT ON COLUMN resource_claim.version IS 'G4 账本版本，与 source_version 不可混用。';
COMMENT ON COLUMN resource_claim.effective_at IS '实际生效时刻；维修使用系统确认时刻，禁止客户端时间倒填。';
COMMENT ON CONSTRAINT resource_claim_scope_ck ON resource_claim IS 'PERSONAL 必带本室 seat_id；WHOLE/REPAIR 必为 NULL；ADMIN 两者皆可。';

-- 可用性查询：按房间/座位取 ACTIVE 占用
CREATE INDEX resource_claim_active_idx
    ON resource_claim (room_id, seat_id, start_at)
    WHERE state = 'ACTIVE';

-- 整间占用按房间检索（WHOLE/REPAIR 的 seat_id 为 NULL）
CREATE INDEX resource_claim_room_state_idx ON resource_claim (room_id, state);

-- 追溯页按区间交集查询
CREATE INDEX resource_claim_interval_idx ON resource_claim (room_id, start_at, end_at);


-- -----------------------------------------------------------------------------
-- claim_history 台账追加历史
--
-- 只追加，不修改不删除。processed_at 与 effective_at 分开保存，
-- 不能用扫描延迟拉长占用（§3.2）。
-- -----------------------------------------------------------------------------
CREATE TABLE claim_history (
    id             uuid        NOT NULL,
    claim_id       uuid        NOT NULL,
    event_id       uuid        NOT NULL,
    state          varchar(12) NOT NULL,
    effective_at   timestamptz NOT NULL,
    processed_at   timestamptz NOT NULL,
    reason         varchar(1000) NOT NULL,
    operation_id   uuid        NOT NULL,
    audit_event_id uuid        NULL,
    source_version bigint      NOT NULL,

    CONSTRAINT claim_history_pk PRIMARY KEY (id),
    CONSTRAINT claim_history_claim_fk FOREIGN KEY (claim_id) REFERENCES resource_claim (id),
    CONSTRAINT claim_history_event_uk UNIQUE (claim_id, event_id),
    CONSTRAINT claim_history_state_ck
        CHECK (state IN ('ACTIVE', 'RELEASED', 'INVALIDATED', 'REJECTED', 'EXPIRED')),
    CONSTRAINT claim_history_source_version_ck CHECK (source_version > 0)
);

COMMENT ON TABLE claim_history IS '台账只追加历史，供 G4-P15 完整读取，不得截断。';

CREATE INDEX claim_history_claim_effective_idx ON claim_history (claim_id, effective_at);


-- -----------------------------------------------------------------------------
-- source_cursor 来源游标
--
-- 每个来源已处理到的最大版本。用于拒绝旧命令（STALE_SOURCE_VERSION）
-- 与「更高版本可释放但不能复活终结来源」（§7.2）。
-- -----------------------------------------------------------------------------
CREATE TABLE source_cursor (
    source_type varchar(10) NOT NULL,
    source_id   uuid        NOT NULL,
    room_id     uuid        NOT NULL,
    max_version bigint      NOT NULL,
    updated_at  timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT source_cursor_pk PRIMARY KEY (source_type, source_id),
    CONSTRAINT source_cursor_source_type_ck
        CHECK (source_type IN ('PERSONAL', 'WHOLE', 'REPAIR', 'ADMIN')),
    CONSTRAINT source_cursor_max_version_ck CHECK (max_version > 0)
);

COMMENT ON TABLE source_cursor IS '来源最大已处理版本，用于幂等与旧命令拒绝。';


-- -----------------------------------------------------------------------------
-- claim_operation_result 台账命令裁决结果
--
-- 每个已受理 operation 一条，持久化正文摘要与原裁决，支撑：
--   * 同 operationId 同正文返回原结果，不同正文 409 IDEMPOTENCY_CONFLICT；
--   * 拒绝结果（DECLINED）与终态（EXPIRED）可原样重放，即使后来冲突已解除（§7.2）；
--   * G4-I06 的 APPLIED / DECLINED 查询。
-- 拒绝体必须是完整 Error（含 traceId 与 details.operationId），否则无法重放原 409/422。
-- -----------------------------------------------------------------------------
CREATE TABLE claim_operation_result (
    operation_id    uuid        NOT NULL,
    source_type     varchar(10) NOT NULL,
    source_id       uuid        NOT NULL,
    room_id         uuid        NOT NULL,
    request_digest  char(64)    NOT NULL,
    outcome         varchar(12) NOT NULL,
    result_state    varchar(12) NULL,
    result_version  bigint      NULL,
    effective_at    timestamptz NULL,
    http_status     integer     NULL,
    rejection_code  varchar(64) NULL,
    rejection_body  jsonb       NULL,
    created_at      timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT claim_operation_result_pk PRIMARY KEY (operation_id),
    CONSTRAINT claim_operation_result_outcome_ck
        CHECK (outcome IN ('APPLIED', 'DECLINED', 'EXPIRED')),
    CONSTRAINT claim_operation_result_state_ck
        CHECK (result_state IS NULL
               OR result_state IN ('ACTIVE', 'RELEASED', 'INVALIDATED', 'REJECTED', 'EXPIRED')),
    CONSTRAINT claim_operation_result_status_ck
        CHECK (http_status IS NULL OR http_status IN (200, 409, 422)),
    -- 拒绝必须同时带错误码与完整错误体
    CONSTRAINT claim_operation_result_rejection_ck CHECK (
        (outcome <> 'DECLINED')
        OR (rejection_code IS NOT NULL AND rejection_body IS NOT NULL AND http_status IS NOT NULL)
    )
);

COMMENT ON TABLE claim_operation_result IS '台账命令的持久裁决；拒绝结果可原样重放，不因冲突解除而改变。';

CREATE INDEX claim_operation_result_source_idx
    ON claim_operation_result (source_type, source_id);


-- -----------------------------------------------------------------------------
-- reservation_user_lock 用户锁行
--
-- 唯一目的：给「同一人跨房重叠检查」提供一个可 SELECT FOR UPDATE 的串行点。
-- 锁顺序（§7.1）：个人创建先取本表行锁，再取 room 行锁；
-- 其他路径一律从 room 行开始，持 room 锁后不得再请求本表。
-- 本表不承载业务事实，行可在首次创建时惰性插入。
-- -----------------------------------------------------------------------------
CREATE TABLE reservation_user_lock (
    user_id    uuid        NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT reservation_user_lock_pk PRIMARY KEY (user_id)
);

COMMENT ON TABLE reservation_user_lock IS '仅作串行点，不存业务状态；个人创建先锁此表再锁 room。';


-- -----------------------------------------------------------------------------
-- resource_impact_preview 影响预览
--
-- token 五分钟有效，绑定操作者 + room.version + change 规范正文摘要 +
-- 受影响来源集合 + 访问资格快照（§7.2）。预览不占资源、不产生业务写。
-- 提交时锁内重算摘要，不一致返回 409 IMPACT_CHANGED。
-- -----------------------------------------------------------------------------
CREATE TABLE resource_impact_preview (
    id             uuid        NOT NULL,
    room_id        uuid        NOT NULL,
    room_version   bigint      NOT NULL,
    actor_id       uuid        NOT NULL,
    change_kind    varchar(20) NOT NULL,
    change_body    jsonb       NOT NULL,
    digest         char(64)    NOT NULL,
    affected_count integer     NOT NULL,
    expires_at     timestamptz NOT NULL,
    created_at     timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT resource_impact_preview_pk PRIMARY KEY (id),
    CONSTRAINT resource_impact_preview_room_fk FOREIGN KEY (room_id) REFERENCES room (id),
    CONSTRAINT resource_impact_preview_room_version_ck CHECK (room_version > 0),
    CONSTRAINT resource_impact_preview_kind_ck
        CHECK (change_kind IN ('ROOM_UPDATE', 'SEAT_UPDATE', 'OPENING_REPLACE', 'RESTRICT')),
    CONSTRAINT resource_impact_preview_count_ck CHECK (affected_count >= 0)
);

COMMENT ON TABLE resource_impact_preview IS '影响预览令牌；五分钟有效，绑定操作者、room.version 与 change 摘要。';

CREATE INDEX resource_impact_preview_actor_idx
    ON resource_impact_preview (actor_id, expires_at);


-- 预览明细。超过 100 条时由 G4-P07 分页读取，不泄露他人身份。
CREATE TABLE resource_impact_item (
    id             uuid         NOT NULL,
    preview_id     uuid         NOT NULL,
    seq            integer      NOT NULL,
    source_type    varchar(10)  NOT NULL,
    source_id      uuid         NOT NULL,
    seat_id        uuid         NULL,
    effect         varchar(20)  NOT NULL,
    reason         varchar(1000) NOT NULL,
    effective_at   timestamptz  NULL,
    source_version bigint       NULL,

    CONSTRAINT resource_impact_item_pk PRIMARY KEY (id),
    CONSTRAINT resource_impact_item_preview_fk
        FOREIGN KEY (preview_id) REFERENCES resource_impact_preview (id) ON DELETE CASCADE,
    CONSTRAINT resource_impact_item_seq_uk UNIQUE (preview_id, seq),
    CONSTRAINT resource_impact_item_source_type_ck
        CHECK (source_type IN ('PERSONAL', 'WHOLE', 'REPAIR', 'ADMIN')),
    CONSTRAINT resource_impact_item_effect_ck
        CHECK (effect IN ('CANCEL_SYSTEM', 'KEEP_NO_SHOW', 'KEEP_COMPLETED'))
);

COMMENT ON TABLE resource_impact_item IS '单个受影响记录的预览明细；普通视图不含他人姓名与用途。';
