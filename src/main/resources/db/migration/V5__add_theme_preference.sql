-- The app's theme the user picked in their preferences: light, dark, or the device's own.
-- Apply before deploying the version that reads it. The default makes every existing user follow
-- their device, as the app did until now.

ALTER TABLE app_user ADD COLUMN IF NOT EXISTS theme TEXT NOT NULL DEFAULT 'SYSTEM';

ALTER TABLE app_user DROP CONSTRAINT IF EXISTS app_user_theme_check;
ALTER TABLE app_user ADD CONSTRAINT app_user_theme_check CHECK (theme IN ('LIGHT', 'DARK', 'SYSTEM'));
