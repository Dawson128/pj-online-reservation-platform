-- Existing passwords remain unchanged. Retire first-login restrictions with a forward migration.
ALTER TABLE m2.account ALTER COLUMN must_change_password SET DEFAULT false;

UPDATE m2.session SET revoked_at = CURRENT_TIMESTAMP
WHERE revoked_at IS NULL AND account_id IN (
    SELECT id FROM m2.account WHERE must_change_password
);

UPDATE m2.account
SET must_change_password = false, version = version + 1, updated_at = CURRENT_TIMESTAMP
WHERE must_change_password;
