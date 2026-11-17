-- The identity provider is no longer Keycloak: the column holds the OIDC subject (sub claim).
-- Renaming a column rewrites the CHECK constraints and indexes that reference it.
ALTER TABLE app_user RENAME COLUMN keycloak_id TO subject_id;
ALTER TABLE account_deletion RENAME COLUMN keycloak_id TO subject_id;

-- The UNIQUE from V1 got Postgres' generated name; renaming its index renames the constraint too.
ALTER INDEX IF EXISTS app_user_keycloak_id_key RENAME TO app_user_subject_id_key;
