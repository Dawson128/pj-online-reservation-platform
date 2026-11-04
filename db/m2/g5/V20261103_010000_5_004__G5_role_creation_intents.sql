-- Forward-only extension; preserve account/department intents and all configured grants.
ALTER TABLE m2.g5_creation_intent DROP CONSTRAINT g5_creation_intent_object_kind_check;
ALTER TABLE m2.g5_creation_intent ADD CONSTRAINT g5_creation_intent_object_kind_check
    CHECK (object_kind IN ('ACCOUNT', 'DEPARTMENT', 'ROLE'));
CREATE INDEX role_binding_role_idx ON m2.role_binding(role_id, account_id);
