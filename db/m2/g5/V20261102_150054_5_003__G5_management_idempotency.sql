CREATE TABLE m2.g5_idempotency (
    actor_id uuid NOT NULL REFERENCES m2.account(id),
    method varchar(10) NOT NULL,
    path varchar(300) NOT NULL,
    request_key uuid NOT NULL,
    request_hmac char(64) NOT NULL,
    response jsonb NOT NULL,
    status integer NOT NULL CHECK (status IN (200, 201)),
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP + interval '24 hours',
    PRIMARY KEY (actor_id, method, path, request_key)
);

CREATE TABLE m2.g5_creation_intent (
    actor_id uuid NOT NULL REFERENCES m2.account(id),
    method varchar(10) NOT NULL,
    path varchar(300) NOT NULL,
    request_key uuid NOT NULL,
    request_hmac char(64) NOT NULL,
    object_kind varchar(20) NOT NULL CHECK (object_kind IN ('ACCOUNT', 'DEPARTMENT')),
    object_id uuid NOT NULL,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (actor_id, method, path, request_key)
);
