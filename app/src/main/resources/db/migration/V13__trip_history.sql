-- Trip history (phase 8). When a trip reached each stage: Trip sets the time in the same update as the status.
-- Trips stored before this migration keep nulls, and their summary is marked approximate. ended_at is set by
-- COMPLETED and by CANCELLED.
ALTER TABLE trips
    ADD COLUMN accepted_at timestamptz,
    ADD COLUMN arrived_at  timestamptz,
    ADD COLUMN started_at  timestamptz,
    ADD COLUMN ended_at    timestamptz;

-- The summary of a completed trip (phase 8a). One row per trip, so computing it again replaces it: the outbox
-- may deliver the completion twice. `summary` is TripSummaryV1. No foreign key to trips, as for trip_moments.
CREATE TABLE trip_summaries (
    trip_id     uuid        PRIMARY KEY,
    summary     jsonb       NOT NULL,
    computed_at timestamptz NOT NULL
);

