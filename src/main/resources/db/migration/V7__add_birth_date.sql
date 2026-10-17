-- Signup completion: the birth date asked once after registration, and whether that step is done.
-- Existing users stay not completed, so they are asked once too.

ALTER TABLE app_user ADD COLUMN IF NOT EXISTS birth_date DATE;
ALTER TABLE app_user ADD COLUMN IF NOT EXISTS registration_completed BOOLEAN NOT NULL DEFAULT FALSE;

-- The birth date is personal: a tombstone has none, like the other fields V6 clears.
ALTER TABLE app_user DROP CONSTRAINT IF EXISTS app_user_tombstone_check;
ALTER TABLE app_user ADD CONSTRAINT app_user_tombstone_check CHECK (
    deleted_at IS NULL OR (
        keycloak_id = 'deleted:' || id::text
        AND user_name = 'deleted-' || id::text
        AND hashtag = 0
        AND first_name IS NULL
        AND last_name IS NULL
        AND profile_pic_url IS NULL
        AND bio IS NULL
        AND cardinality(interests) = 0
        AND birth_date IS NULL));
