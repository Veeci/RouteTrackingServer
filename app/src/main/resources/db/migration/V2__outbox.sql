-- V2: the transactional outbox, shared by every context.
-- A use case writes its events here in the same transaction as its data change, so an event exists
-- if and only if the change was committed. A relay (phase 3) delivers unpublished rows and sets published_at.

CREATE TABLE outbox (
    id           bigserial   PRIMARY KEY,
    event_type   text        NOT NULL,
    payload      jsonb       NOT NULL,
    created_at   timestamptz NOT NULL DEFAULT now(),
    published_at timestamptz
);

CREATE INDEX outbox_unpublished ON outbox (id) WHERE published_at IS NULL;
