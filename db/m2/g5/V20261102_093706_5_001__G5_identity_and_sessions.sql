CREATE TABLE m2.department (
    id uuid PRIMARY KEY,
    code varchar(40) NOT NULL UNIQUE,
    name varchar(100) NOT NULL,
    enabled boolean NOT NULL DEFAULT true,
    version bigint NOT NULL DEFAULT 1 CHECK (version > 0),
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE m2.account (
    id uuid PRIMARY KEY,
    user_code varchar(40) NOT NULL UNIQUE,
    display_name varchar(100) NOT NULL,
    department_id uuid NOT NULL REFERENCES m2.department(id),
    email varchar(254) NOT NULL,
    password_hash varchar(512) NOT NULL,
    enabled boolean NOT NULL DEFAULT true,
    must_change_password boolean NOT NULL DEFAULT true,
    version bigint NOT NULL DEFAULT 1 CHECK (version > 0),
    permission_version bigint NOT NULL DEFAULT 1 CHECK (permission_version > 0),
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE m2.permission (
    action varchar(80) PRIMARY KEY,
    description varchar(200) NOT NULL,
    owner_group varchar(2) NOT NULL CHECK (owner_group IN ('G1','G2','G3','G4','G5','G6'))
);

CREATE TABLE m2.role (
    id uuid PRIMARY KEY,
    key varchar(60) NOT NULL UNIQUE,
    name varchar(100) NOT NULL UNIQUE,
    built_in boolean NOT NULL DEFAULT false,
    version bigint NOT NULL DEFAULT 1 CHECK (version > 0),
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE m2.role_grant (
    id uuid PRIMARY KEY,
    role_id uuid NOT NULL REFERENCES m2.role(id),
    action varchar(80) NOT NULL REFERENCES m2.permission(action),
    scope_kind varchar(10) NOT NULL CHECK (scope_kind IN ('SCHOOL','DEPARTMENT','ROOM','SYSTEM')),
    department_id uuid REFERENCES m2.department(id),
    room_id uuid,
    CHECK ((scope_kind IN ('SCHOOL','SYSTEM') AND department_id IS NULL AND room_id IS NULL)
        OR (scope_kind = 'DEPARTMENT' AND department_id IS NOT NULL AND room_id IS NULL)
        OR (scope_kind = 'ROOM' AND room_id IS NOT NULL AND department_id IS NULL)),
    UNIQUE NULLS NOT DISTINCT (role_id, action, scope_kind, department_id, room_id)
);

CREATE TABLE m2.role_binding (
    id uuid PRIMARY KEY,
    account_id uuid NOT NULL REFERENCES m2.account(id),
    role_id uuid NOT NULL REFERENCES m2.role(id),
    version bigint NOT NULL DEFAULT 1 CHECK (version > 0),
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (account_id, role_id)
);

CREATE TABLE m2.session (
    id uuid PRIMARY KEY,
    account_id uuid NOT NULL REFERENCES m2.account(id),
    token_hash char(64) NOT NULL UNIQUE,
    csrf_hash char(64) NOT NULL,
    account_version bigint NOT NULL CHECK (account_version > 0),
    permission_version bigint NOT NULL CHECK (permission_version > 0),
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at timestamptz NOT NULL,
    revoked_at timestamptz,
    CHECK (expires_at > created_at)
);
CREATE INDEX session_account_idx ON m2.session(account_id);

CREATE TABLE m2.g5_auth_throttle (
    bucket varchar(30) NOT NULL,
    subject_hash char(64) NOT NULL,
    failures integer NOT NULL DEFAULT 0 CHECK (failures >= 0),
    window_started_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    blocked_until timestamptz,
    PRIMARY KEY (bucket, subject_hash)
);

CREATE TABLE m2.g5_audit_event (
    event_id uuid PRIMARY KEY,
    event jsonb NOT NULL,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE m2.g5_outbox (
    event_id uuid PRIMARY KEY REFERENCES m2.g5_audit_event(event_id),
    destination varchar(10) NOT NULL DEFAULT 'G6',
    status varchar(20) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','DELIVERED','BLOCKED')),
    attempts integer NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    delivered_at timestamptz
);

CREATE TABLE m2.g5_admin_guard (id integer PRIMARY KEY CHECK (id = 1));
INSERT INTO m2.g5_admin_guard(id) VALUES (1);
