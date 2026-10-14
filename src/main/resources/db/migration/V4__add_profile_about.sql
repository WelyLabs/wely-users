-- What a user says about themselves on their profile: a short bio and a few interests.
-- Apply before deploying the version that reads them. Both are optional; the defaults make every
-- existing user valid as they are.

ALTER TABLE app_user ADD COLUMN IF NOT EXISTS bio TEXT;
ALTER TABLE app_user ADD COLUMN IF NOT EXISTS interests TEXT[] NOT NULL DEFAULT '{}';
