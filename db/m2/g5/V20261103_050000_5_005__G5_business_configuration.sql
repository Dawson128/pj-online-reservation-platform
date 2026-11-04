CREATE TABLE m2.business_config (
    id integer PRIMARY KEY CHECK (id=1),
    version bigint NOT NULL DEFAULT 1 CHECK (version>0),
    effective_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    reservation_max_hours integer NOT NULL CHECK (reservation_max_hours BETWEEN 1 AND 8),
    reservation_advance_days integer NOT NULL CHECK (reservation_advance_days BETWEEN 1 AND 7),
    application_advance_days integer NOT NULL CHECK (application_advance_days BETWEEN 1 AND 14),
    scan_interval_seconds integer NOT NULL CHECK (scan_interval_seconds BETWEEN 1 AND 30),
    notification_retry_limit integer NOT NULL CHECK (notification_retry_limit BETWEEN 0 AND 5)
);
INSERT INTO m2.business_config(id,reservation_max_hours,reservation_advance_days,application_advance_days,scan_interval_seconds,notification_retry_limit)
VALUES (1,4,7,14,10,5);

CREATE TABLE m2.g5_config_history (
    version bigint PRIMARY KEY CHECK (version>0),
    effective_at timestamptz NOT NULL,
    actor_id uuid REFERENCES m2.account(id),
    values jsonb NOT NULL CHECK (jsonb_typeof(values)='object')
);
INSERT INTO m2.g5_config_history(version,effective_at,actor_id,values)
SELECT version,effective_at,NULL,jsonb_build_object('reservationMaxHours',reservation_max_hours,'reservationAdvanceDays',reservation_advance_days,
    'applicationAdvanceDays',application_advance_days,'scanIntervalSeconds',scan_interval_seconds,'notificationRetryLimit',notification_retry_limit)
FROM m2.business_config WHERE id=1;
