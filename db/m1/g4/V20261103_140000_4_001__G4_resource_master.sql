-- =============================================================================
-- G4 空间资源：资源主数据
--
-- owner : G4（reservation-service 内 modules/g4 与 integration）
-- schema: m1（由 Flyway default-schema 指定，本文件所有对象不写 schema 前缀）
-- 依据  : 技术文档 v2.0 §3.1（表、聚合和约束）、§3.2（历史与统计）、
--         §4.3（Room/Seat/Opening DTO）、§6.1—6.3（管理功能）
-- 命名  : V<UTC yyyyMMdd>_<HHmmss>_4_<001>__G4_<ascii>.sql，由
--         infra/scripts/check-repository.mjs 校验，已合入后不得改名或改校验和。
--
-- 本文件只建 G4 拥有的主数据表。占用台账见 002，运行支撑表见 003。
-- 所有权说明：本 schema 内 G1 与 G4 各自只写自己的目录与表；本文件的表不属于 G1。
-- =============================================================================


-- -----------------------------------------------------------------------------
-- room 房间
--
-- 约束要点：
--   * code 全局唯一，创建后不可修改（由应用层保证；主数据变更不走 code 通道）。
--   * school_wide=true 时 dept_id 必须为 NULL；false 时必须有归属院系。
--     该约束与 §4.3「Room.schoolWide=true 要求 deptId=null」一致。
--   * capacity 只用于整室申请的人数上限校验，不限制已登记座位数。
--   * enabled=false 表示注销，历史保留；恢复不复活旧记录。
--   * version 为乐观并发版本，所有权在 G4；每次房内可用性写都递增。
-- -----------------------------------------------------------------------------
CREATE TABLE room (
    id          uuid         NOT NULL,
    version     bigint       NOT NULL,
    code        varchar(40)  NOT NULL,
    name        varchar(100) NOT NULL,
    location    varchar(200) NOT NULL,
    area        varchar(100) NOT NULL,
    room_type   varchar(20)  NOT NULL,
    capacity    integer      NOT NULL,
    school_wide boolean      NOT NULL,
    dept_id     uuid         NULL,
    enabled     boolean      NOT NULL,
    created_at  timestamptz  NOT NULL DEFAULT now(),
    updated_at  timestamptz  NOT NULL DEFAULT now(),

    CONSTRAINT room_pk PRIMARY KEY (id),
    CONSTRAINT room_code_uk UNIQUE (code),
    CONSTRAINT room_version_ck CHECK (version > 0),
    CONSTRAINT room_code_ck CHECK (btrim(code) <> ''),
    CONSTRAINT room_type_ck CHECK (room_type IN ('CLASSROOM', 'LIBRARY', 'OTHER')),
    CONSTRAINT room_capacity_ck CHECK (capacity BETWEEN 1 AND 10000),
    CONSTRAINT room_scope_ck CHECK (
        (school_wide AND dept_id IS NULL)
        OR ((NOT school_wide) AND dept_id IS NOT NULL)
    )
);

COMMENT ON TABLE room IS 'G4 房间主数据。enabled=false 为注销，历史保留。';
COMMENT ON COLUMN room.version IS '乐观并发版本，房内可用性写操作递增。';
COMMENT ON COLUMN room.capacity IS '仅用于整室申请人数上限，不限制座位登记数。';
COMMENT ON COLUMN room.school_wide IS 'true 时全校开放且 dept_id 为 NULL；false 时归属 dept_id。';

-- 管理端按范围与状态检索房间
CREATE INDEX room_enabled_dept_idx ON room (enabled, dept_id);
CREATE INDEX room_type_idx ON room (room_type);


-- -----------------------------------------------------------------------------
-- seat 座位
--
-- 约束要点：
--   * code 全局唯一。
--   * (id, room_id) 唯一：为了支持 resource_claim 的复合外键，
--     从数据库层面强制「claim 上的 seat_id 必须确属该 room_id」，
--     对应技术文档 §7.1「任何非 null 的 seatId 必须确属 roomId」。
--   * 已有历史的座位不允许改编号或迁室：由触发器 seat_identity_immutable 强制，
--     对应需求 §6.1「已产生历史的座位不允许改编号或迁入另一房间」。
--     正确做法是注销旧座、登记新座。
-- -----------------------------------------------------------------------------
CREATE TABLE seat (
    id          uuid        NOT NULL,
    version     bigint      NOT NULL,
    room_id     uuid        NOT NULL,
    code        varchar(40) NOT NULL,
    socket_type varchar(10) NOT NULL,
    enabled     boolean     NOT NULL,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT seat_pk PRIMARY KEY (id),
    CONSTRAINT seat_code_uk UNIQUE (code),
    CONSTRAINT seat_id_room_uk UNIQUE (id, room_id),
    CONSTRAINT seat_room_fk FOREIGN KEY (room_id) REFERENCES room (id),
    CONSTRAINT seat_version_ck CHECK (version > 0),
    CONSTRAINT seat_code_ck CHECK (btrim(code) <> ''),
    CONSTRAINT seat_socket_type_ck CHECK (socket_type IN ('NONE', 'FIXED', 'RAIL'))
);

COMMENT ON TABLE seat IS 'G4 座位主数据。编号与所属房间一经登记不可变更。';
COMMENT ON CONSTRAINT seat_id_room_uk ON seat IS '支撑 resource_claim 的复合外键 (seat_id, room_id)。';

CREATE INDEX seat_room_enabled_idx ON seat (room_id, enabled);


-- 座位身份不可变：编号与所属房间禁止 UPDATE。
-- 需要变更位置时注销旧座并登记新座，以保留原签到与预约历史。
CREATE FUNCTION seat_identity_immutable() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.code IS DISTINCT FROM OLD.code THEN
        RAISE EXCEPTION 'seat.code is immutable (seat_id=%)', OLD.id
            USING HINT = '注销旧座位并登记新座位，不要改编号';
    END IF;
    IF NEW.room_id IS DISTINCT FROM OLD.room_id THEN
        RAISE EXCEPTION 'seat.room_id is immutable (seat_id=%)', OLD.id
            USING HINT = '注销旧座位并登记新座位，不要迁室';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER seat_identity_immutable_trg
    BEFORE UPDATE ON seat
    FOR EACH ROW
    EXECUTE FUNCTION seat_identity_immutable();


-- -----------------------------------------------------------------------------
-- department_reference 院系引用
--
-- 院系事实归 G5；G4 只保存被引用过的院系 ID 与展示快照。
-- 历史停用院系仍可显示（快照保留），但不能新绑定——新绑定由应用层校验。
-- -----------------------------------------------------------------------------
CREATE TABLE department_reference (
    dept_id        uuid         NOT NULL,
    code           varchar(40)  NULL,
    name           varchar(100) NULL,
    enabled_snapshot boolean    NOT NULL DEFAULT true,
    first_seen_at  timestamptz  NOT NULL DEFAULT now(),
    last_seen_at   timestamptz  NOT NULL DEFAULT now(),

    CONSTRAINT department_reference_pk PRIMARY KEY (dept_id)
);

COMMENT ON TABLE department_reference IS 'G4 侧院系引用与展示快照；院系事实以 G5 为准。';


-- -----------------------------------------------------------------------------
-- opening_rule 开放规则（版本化）
--
-- 一个房间在同一时刻只有一条当前规则；规则整体替换而非增量修改
-- （需求 §6.2、技术 §5.4 G4-P11）。
--   * opening_weekly：周规则，weekday 1=周一…7=周日，每日期最多 8 段（seq 1..8）。
--   * opening_exception：指定日期例外，当日整体覆盖周规则（不是叠加），
--     intervals 为空表示整日关闭——用「无记录」表达空，需由应用层区分
--     「该日无例外」与「该日例外但无开放区间」，因此单独用 is_closed 标记。
-- 分钟数用 0..1440 表示 HH:mm，1440 即 24:00，仅允许出现在结束位。
-- -----------------------------------------------------------------------------
CREATE TABLE opening_rule (
    id         uuid        NOT NULL,
    room_id    uuid        NOT NULL,
    version    bigint      NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT opening_rule_pk PRIMARY KEY (id),
    CONSTRAINT opening_rule_room_version_uk UNIQUE (room_id, version),
    CONSTRAINT opening_rule_room_fk FOREIGN KEY (room_id) REFERENCES room (id),
    CONSTRAINT opening_rule_version_ck CHECK (version > 0)
);

COMMENT ON TABLE opening_rule IS 'G4 开放规则版本。整体替换，不增量修改。';

CREATE TABLE opening_weekly (
    id           uuid     NOT NULL,
    rule_id      uuid     NOT NULL,
    weekday      smallint NOT NULL,
    seq          smallint NOT NULL,
    start_minute smallint NOT NULL,
    end_minute   smallint NOT NULL,

    CONSTRAINT opening_weekly_pk PRIMARY KEY (id),
    CONSTRAINT opening_weekly_rule_fk FOREIGN KEY (rule_id) REFERENCES opening_rule (id) ON DELETE CASCADE,
    CONSTRAINT opening_weekly_weekday_ck CHECK (weekday BETWEEN 1 AND 7),
    CONSTRAINT opening_weekly_seq_ck CHECK (seq BETWEEN 1 AND 8),
    CONSTRAINT opening_weekly_start_ck CHECK (start_minute BETWEEN 0 AND 1439),
    CONSTRAINT opening_weekly_end_ck CHECK (end_minute BETWEEN 1 AND 1440),
    CONSTRAINT opening_weekly_order_ck CHECK (start_minute < end_minute),
    CONSTRAINT opening_weekly_unique_uk UNIQUE (rule_id, weekday, seq)
);

CREATE TABLE opening_exception (
    id            uuid     NOT NULL,
    rule_id       uuid     NOT NULL,
    business_date date     NOT NULL,
    seq           smallint NOT NULL,
    start_minute  smallint NOT NULL,
    end_minute    smallint NOT NULL,

    CONSTRAINT opening_exception_pk PRIMARY KEY (id),
    CONSTRAINT opening_exception_rule_fk FOREIGN KEY (rule_id) REFERENCES opening_rule (id) ON DELETE CASCADE,
    CONSTRAINT opening_exception_seq_ck CHECK (seq BETWEEN 1 AND 8),
    CONSTRAINT opening_exception_start_ck CHECK (start_minute BETWEEN 0 AND 1439),
    CONSTRAINT opening_exception_end_ck CHECK (end_minute BETWEEN 1 AND 1440),
    CONSTRAINT opening_exception_order_ck CHECK (start_minute < end_minute),
    CONSTRAINT opening_exception_unique_uk UNIQUE (rule_id, business_date, seq)
);

COMMENT ON TABLE opening_exception IS '指定日期例外，整体覆盖周规则；无记录=无例外，有记录但无可用段=整日关闭。';
CREATE INDEX opening_exception_rule_date_idx ON opening_exception (rule_id, business_date);


-- -----------------------------------------------------------------------------
-- resource_version_history 房间/座位版本历史
--
-- 只追加。seat_id 为 NULL 表示房间级变更（归属、开放、容量等）。
-- 「历史停用院系仍可显示」依赖此表保存归属快照，不反向查 G5。
-- -----------------------------------------------------------------------------
CREATE TABLE resource_version_history (
    id          uuid        NOT NULL,
    room_id     uuid        NOT NULL,
    seat_id     uuid        NULL,
    version     bigint      NOT NULL,
    changed_at  timestamptz NOT NULL,
    actor_id    uuid        NULL,
    action      varchar(80) NOT NULL,
    before_state jsonb      NOT NULL DEFAULT '{}'::jsonb,
    after_state  jsonb      NOT NULL DEFAULT '{}'::jsonb,

    CONSTRAINT resource_version_history_pk PRIMARY KEY (id),
    CONSTRAINT resource_version_history_room_fk FOREIGN KEY (room_id) REFERENCES room (id),
    CONSTRAINT resource_version_history_seat_fk FOREIGN KEY (seat_id, room_id) REFERENCES seat (id, room_id),
    CONSTRAINT resource_version_history_version_ck CHECK (version > 0),
    -- 房间级变更 seat_id 为 NULL，用 NULLS NOT DISTINCT 让唯一约束对空值也生效
    CONSTRAINT resource_version_history_unique_uk
        UNIQUE NULLS NOT DISTINCT (room_id, seat_id, version)
);

COMMENT ON TABLE resource_version_history IS '资源主数据只追加版本历史；seat_id 为 NULL 表示房间级变更。';

CREATE INDEX resource_version_history_room_changed_idx
    ON resource_version_history (room_id, changed_at DESC);
