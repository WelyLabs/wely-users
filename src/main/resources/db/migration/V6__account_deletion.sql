-- Account deletion: a tombstone keeps the businessId, the handle is retired, the cleanup is queued.

-- Null for a live user; the deletion time for a tombstone.
ALTER TABLE app_user ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMPTZ;

-- No personal field survives on a tombstone: the placeholders are the only values allowed.
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
        AND cardinality(interests) = 0));

-- Keyset listing of tombstones, for the consumers' reconciliation.
CREATE INDEX IF NOT EXISTS idx_app_user_deleted ON app_user (deleted_at, id) WHERE deleted_at IS NOT NULL;

-- The single definition of a retired handle's digest; every query that writes or checks one calls it.
CREATE OR REPLACE FUNCTION retired_handle_digest(handle_name TEXT, handle_tag INTEGER) RETURNS BYTEA
    LANGUAGE sql STABLE STRICT
AS $$ SELECT sha256(convert_to(lower(handle_name) || '#' || handle_tag::text, 'UTF8')) $$;

-- Handles of deleted accounts, kept as digests only, so they are never handed out again.
CREATE TABLE IF NOT EXISTS retired_handle (
    handle_digest BYTEA       PRIMARY KEY,
    retired_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- One row per deleted account: the Keycloak and avatar cleanup still to do, kept afterwards as an audit.
CREATE TABLE IF NOT EXISTS account_deletion (
    user_id             UUID        PRIMARY KEY REFERENCES app_user (id),
    keycloak_id         TEXT,                               -- nulled once Keycloak confirmed the deletion
    requested_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    identity_deleted_at TIMESTAMPTZ,
    media_deleted_at    TIMESTAMPTZ,
    attempts            INTEGER     NOT NULL DEFAULT 0,
    next_attempt_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_error          TEXT,                               -- an error code, never a message
    CONSTRAINT account_deletion_identity_check CHECK ((identity_deleted_at IS NULL) = (keycloak_id IS NOT NULL))
);

-- The worker only reads unfinished jobs.
CREATE INDEX IF NOT EXISTS idx_account_deletion_due
    ON account_deletion (next_attempt_at)
    WHERE identity_deleted_at IS NULL OR media_deleted_at IS NULL;

-- The deletion transaction drops a user's other outbox rows by aggregate_id.
CREATE INDEX IF NOT EXISTS idx_outbox_event_aggregate ON outbox_event (aggregate_id);
