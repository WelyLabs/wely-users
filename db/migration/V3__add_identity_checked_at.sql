-- When the user's Keycloak account was confirmed to exist. Null until OrphanUserReaper checks
-- it: a user Keycloak does not know is the leftover of a registration Keycloak rolled back.
-- Apply before deploying the version that reads it, and before the demo seed that sets it.

ALTER TABLE app_user ADD COLUMN IF NOT EXISTS identity_checked_at TIMESTAMPTZ;

-- Users that exist when the column is added were created by the earlier provisioning, which
-- read the account from Keycloak first: they are not leftovers. Without this, the demo's
-- fictional friends, who have no Keycloak account by design, would be deleted.
UPDATE app_user SET identity_checked_at = now() WHERE identity_checked_at IS NULL;
