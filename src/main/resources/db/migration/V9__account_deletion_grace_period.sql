-- Deletion is scheduled, not immediate: the erasure happens at the end of a grace period, and any
-- sign-in before it cancels the whole thing.

-- Null unless a deletion is scheduled; cleared on cancellation and on the erasure itself.
ALTER TABLE app_user ADD COLUMN IF NOT EXISTS deletion_due_at TIMESTAMPTZ;

-- A tombstone is already erased, so it can never still be scheduled.
ALTER TABLE app_user DROP CONSTRAINT IF EXISTS app_user_deletion_due_check;
ALTER TABLE app_user ADD CONSTRAINT app_user_deletion_due_check CHECK (
    deleted_at IS NULL OR deletion_due_at IS NULL);
