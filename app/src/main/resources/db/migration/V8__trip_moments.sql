-- Moments the server noticed on a trip (phase 4), such as "driver arriving". Kept for the trip's history;
-- guests get them through the outbox, which is written in the same transaction. `payload` is
-- TripMomentRaisedV1. No foreign key to trips: like every table here, rows refer to others by id.
CREATE TABLE trip_moments (
    id      bigserial   PRIMARY KEY,
    trip_id uuid        NOT NULL,
    kind    text        NOT NULL,
    payload jsonb       NOT NULL,
    at      timestamptz NOT NULL
);

-- A trip's moments in time order (history, phase 8).
CREATE INDEX trip_moments_trip_at ON trip_moments (trip_id, at);

-- What each rule remembers between two batches of one trip (for example how many fixes in a row were near
-- the pickup). Read with FOR UPDATE and written in the transaction that uses it, so two batches of one trip
-- cannot both count the same state.
CREATE TABLE trip_rule_state (
    trip_id    uuid        NOT NULL,
    rule       text        NOT NULL,
    state      jsonb       NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (trip_id, rule)
);
