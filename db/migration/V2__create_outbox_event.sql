-- Transactional outbox for calendar-users-api.
--
-- Like V1, this file is kept off the classpath so nothing runs it implicitly.
-- Apply it before deploying the version of the service that writes to it: the
-- provisioning path inserts here inside the same transaction as app_user, so a
-- missing table breaks signup rather than degrading it.

CREATE TABLE IF NOT EXISTS outbox_event (
    -- BIGSERIAL, so ORDER BY id is insertion order and the relay publishes in the
    -- order the writes committed.
    id           BIGSERIAL   PRIMARY KEY,

    -- The stream this event belongs to. Used as the Kafka partition key, so every
    -- event about one user lands in one partition and stays ordered relative to the
    -- others about that user. Ordering across users is meaningless and Kafka does
    -- not promise it.
    aggregate_id UUID        NOT NULL,

    -- Names the payload shape. The relay maps it to a destination binding and to the
    -- class the payload deserialises into, so an unknown type is a failure the relay
    -- reports rather than a message silently sent to the wrong topic.
    type         TEXT        NOT NULL,

    -- TEXT rather than JSONB: the r2dbc-postgresql codec maps jsonb to its own Json
    -- type rather than to String, and the relay only moves these bytes from one place
    -- to another. Being readable in psql is the only requirement.
    payload      TEXT        NOT NULL,

    -- TIMESTAMPTZ, unlike app_user.joined_date. Retention is computed from created_at
    -- and the relay's lag from published_at; an instant without a zone is a bug
    -- waiting for the next clock change.
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ,

    -- A payload that cannot be sent would otherwise sit at the head of the queue and
    -- block everything behind it, since the relay reads in id order. Past a cap the
    -- relay stops picking it up and leaves it visible here instead.
    attempts     INTEGER     NOT NULL DEFAULT 0,
    last_error   TEXT
);

-- Partial index: the relay only ever asks for unpublished rows, and published rows
-- stay until the purge. Indexing the whole table would grow with the history rather
-- than with the backlog.
CREATE INDEX IF NOT EXISTS idx_outbox_event_pending
    ON outbox_event (id)
    WHERE published_at IS NULL;

-- Supports the purge, which deletes published rows older than the retention window.
CREATE INDEX IF NOT EXISTS idx_outbox_event_published_at
    ON outbox_event (published_at)
    WHERE published_at IS NOT NULL;
