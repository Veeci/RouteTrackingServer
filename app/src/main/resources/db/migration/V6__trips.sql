-- Trips (trip context). One row per ride. `version` counts the saved changes: an update only succeeds
-- if the version is still the one that was read (optimistic locking, see TripRepository.update).
CREATE TABLE trips (
    id              uuid             PRIMARY KEY,
    guest_id        uuid             NOT NULL,
    driver_id       uuid,
    pickup_lat      double precision NOT NULL CHECK (pickup_lat BETWEEN -90 AND 90),
    pickup_lng      double precision NOT NULL CHECK (pickup_lng BETWEEN -180 AND 180),
    destination_lat double precision NOT NULL CHECK (destination_lat BETWEEN -90 AND 90),
    destination_lng double precision NOT NULL CHECK (destination_lng BETWEEN -180 AND 180),
    status          text             NOT NULL
        CHECK (status IN ('REQUESTED', 'ACCEPTED', 'ARRIVED', 'IN_PROGRESS', 'COMPLETED', 'CANCELLED')),
    version         bigint           NOT NULL CHECK (version > 0),
    created_at      timestamptz      NOT NULL,
    updated_at      timestamptz      NOT NULL,
    -- Every stage after REQUESTED has a driver, except a trip cancelled before anyone accepted it.
    CONSTRAINT trips_driver_when_accepted CHECK (driver_id IS NOT NULL OR status IN ('REQUESTED', 'CANCELLED'))
);

-- Open trips for drivers, oldest first, paged by (created_at, id): TripRepository.findByStatus.
CREATE INDEX trips_status_created ON trips (status, created_at, id);

-- A driver drives one trip at a time. The service checks it first; this index also holds when two
-- requests race past that check (TC-3-DB-04). It contains only active trips, so it stays small.
CREATE UNIQUE INDEX one_active_trip_per_driver ON trips (driver_id)
    WHERE status IN ('ACCEPTED', 'ARRIVED', 'IN_PROGRESS');
