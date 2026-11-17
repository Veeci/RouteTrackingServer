-- Phase 11b: the fleet read model. One row per driver, kept by the fleet context from events (tracking, trip,
-- identity) and rebuilt from the source tables on demand. No PostGIS: plain lat and lng with a btree index answer a
-- map area over about 10 000 drivers.

CREATE TABLE fleet_drivers (
    driver_id       uuid             PRIMARY KEY,
    -- Null until the identity event arrives; drivers made only from a token have no account row.
    display_name    text,
    enabled         boolean          NOT NULL DEFAULT true,
    -- The newer of position_at and status_at. "Online" is last_seen_at within the stale time, computed when read.
    last_seen_at    timestamptz,
    lat             double precision,
    lng             double precision,
    -- 4-byte floats, as in `fixes`, so a rebuild from `fixes` gives the same value as the event did.
    speed_mps       real,
    bearing_deg     real,
    battery_pct     smallint,
    is_charging     boolean,
    motion          text,
    -- The device times of the stored position, status and trip stage: a change applies only if it is newer.
    position_at     timestamptz,
    status_at       timestamptz,
    current_trip_id uuid,
    trip_status     text,
    trip_status_at  timestamptz,
    -- Server time of the last write. The live fleet socket (11c) reads the rows changed since its last tick.
    updated_at      timestamptz      NOT NULL
);

-- A map area: lat BETWEEN and lng BETWEEN.
CREATE INDEX fleet_drivers_position ON fleet_drivers (lat, lng);
-- The driver list, newest seen first, paged by (last_seen_at, driver_id). NULLS LAST must match the ORDER BY, or
-- Postgres cannot read the index in order (a DESC index puts nulls first by default).
CREATE INDEX fleet_drivers_last_seen ON fleet_drivers (last_seen_at DESC NULLS LAST, driver_id);
-- The live fleet tick (11c).
CREATE INDEX fleet_drivers_updated ON fleet_drivers (updated_at);

-- The driver detail asks for a driver's latest session health; V16 indexed session_health by start time only.
CREATE INDEX session_health_driver ON session_health (driver_id, started_at DESC);
