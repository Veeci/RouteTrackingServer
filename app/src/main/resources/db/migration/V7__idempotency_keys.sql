-- Responses to requests that carried an Idempotency-Key header (platform, any context may use it).
-- A retry with the same key and the same request gets the stored response, so a client can safely repeat a
-- POST after a network error. Only successful responses are stored. Rows older than 24 h are ignored and
-- replaced on the next use of the key.
CREATE TABLE idempotency_keys (
    principal    text        NOT NULL,  -- the caller's user id: two users may use the same key
    key          text        NOT NULL,
    request_hash text        NOT NULL,  -- SHA-256 of method, path and body, to detect a reused key
    status       integer     NOT NULL,
    headers      jsonb       NOT NULL,
    body         text        NOT NULL,
    created_at   timestamptz NOT NULL,
    PRIMARY KEY (principal, key)
);
