-- Transactional outbox: USER_CREATED is written here in the same transaction as the user,
-- then sent to Kafka by OutboxRelay. Apply before deploying the version that writes to it.

CREATE TABLE IF NOT EXISTS outbox_event (
    id           BIGSERIAL   PRIMARY KEY,   -- insertion order = publication order
    aggregate_id UUID        NOT NULL,      -- the user; also the Kafka partition key
    type         TEXT        NOT NULL,      -- one of OutboxEventType
    payload      TEXT        NOT NULL,      -- the event as JSON
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ,               -- null until sent
    attempts     INTEGER     NOT NULL DEFAULT 0,
    last_error   TEXT
);

-- The relay only reads unpublished rows.
CREATE INDEX IF NOT EXISTS idx_outbox_event_pending
    ON outbox_event (id)
    WHERE published_at IS NULL;

-- For the purge of old published rows.
CREATE INDEX IF NOT EXISTS idx_outbox_event_published_at
    ON outbox_event (published_at)
    WHERE published_at IS NOT NULL;
