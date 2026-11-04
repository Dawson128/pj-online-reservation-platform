-- Forward-only G5 delivery state. Original events and outbox IDs remain unchanged.
CREATE TABLE m2.g5_operation (
    id uuid PRIMARY KEY,
    outbox_event_id uuid NOT NULL UNIQUE REFERENCES m2.g5_outbox(event_id),
    source_type varchar(10) NOT NULL,
    source_id uuid NOT NULL,
    source_version bigint NOT NULL CHECK(source_version>0),
    actor_id uuid,
    trace_id uuid NOT NULL,
    status varchar(12) NOT NULL CHECK(status IN ('PENDING','RUNNING','RETRY_WAIT','SUCCEEDED','BLOCKED')),
    version bigint NOT NULL DEFAULT 1 CHECK(version>0),
    attempt_count integer NOT NULL DEFAULT 0 CHECK(attempt_count>=0),
    next_retry_at timestamptz,
    lease_token uuid,
    lease_until timestamptz,
    first_failure_at timestamptz,
    error_code varchar(80),
    needs_attention boolean NOT NULL DEFAULT false,
    duplicate_receipt boolean,
    accepted_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    CHECK((status='RUNNING')=(lease_token IS NOT NULL AND lease_until IS NOT NULL))
);
CREATE INDEX g5_operation_delivery_idx ON m2.g5_operation(status,next_retry_at,lease_until,created_at,id);
CREATE TABLE m2.g5_outbox_attempt (
    id uuid PRIMARY KEY,
    operation_id uuid NOT NULL REFERENCES m2.g5_operation(id),
    sequence integer NOT NULL CHECK(sequence>0),
    lease_token uuid NOT NULL,
    started_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    finished_at timestamptz,
    outcome varchar(10) CHECK(outcome IN ('ACCEPTED','UNKNOWN','REJECTED')),
    error_code varchar(80),
    UNIQUE(operation_id,sequence)
);
-- Both backfill and future inserts obtain metadata from the original immutable event.
CREATE FUNCTION m2.g5_create_delivery_operation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO m2.g5_operation(id,outbox_event_id,source_type,source_id,source_version,actor_id,trace_id,status,
        attempt_count,needs_attention,accepted_at,created_at,next_retry_at)
    SELECT NEW.event_id,NEW.event_id,event->'aggregate'->>'type',(event->'aggregate'->>'id')::uuid,
        (event->>'aggregateVersion')::bigint,(event->>'actorId')::uuid,(event->>'traceId')::uuid,
        CASE NEW.status WHEN 'DELIVERED' THEN 'SUCCEEDED' WHEN 'BLOCKED' THEN 'BLOCKED' ELSE 'PENDING' END,
        NEW.attempts,NEW.status='BLOCKED',NEW.created_at,NEW.created_at,
        CASE WHEN NEW.status='PENDING' THEN NEW.created_at ELSE NULL END
    FROM m2.g5_audit_event WHERE event_id=NEW.event_id;
    RETURN NEW;
END $$;
CREATE TRIGGER g5_outbox_delivery_operation AFTER INSERT ON m2.g5_outbox FOR EACH ROW EXECUTE FUNCTION m2.g5_create_delivery_operation();
INSERT INTO m2.g5_operation(id,outbox_event_id,source_type,source_id,source_version,actor_id,trace_id,status,
    attempt_count,needs_attention,accepted_at,created_at,next_retry_at)
SELECT o.event_id,o.event_id,e.event->'aggregate'->>'type',(e.event->'aggregate'->>'id')::uuid,
    (e.event->>'aggregateVersion')::bigint,(e.event->>'actorId')::uuid,(e.event->>'traceId')::uuid,
    CASE o.status WHEN 'DELIVERED' THEN 'SUCCEEDED' WHEN 'BLOCKED' THEN 'BLOCKED' ELSE 'PENDING' END,
    o.attempts,o.status='BLOCKED',o.created_at,o.created_at,CASE WHEN o.status='PENDING' THEN o.created_at ELSE NULL END
FROM m2.g5_outbox o JOIN m2.g5_audit_event e ON e.event_id=o.event_id;
