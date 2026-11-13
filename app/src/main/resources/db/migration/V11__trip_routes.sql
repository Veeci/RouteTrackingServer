-- Planned road routes of a trip (trip context, phase 6). A trip gets its TRIP route (pickup to destination) when
-- it is booked. APPROACH (the driver's position to the pickup) is reserved for phase 7. `version` numbers the
-- re-plans of one kind, so a later re-route adds a row instead of overwriting the one clients may have drawn.
CREATE TABLE trip_routes (
    trip_id     uuid             NOT NULL REFERENCES trips (id) ON DELETE CASCADE,
    kind        text             NOT NULL CHECK (kind IN ('TRIP', 'APPROACH')),
    version     integer          NOT NULL CHECK (version > 0),
    -- Google Encoded Polyline, precision 5: about 4 bytes a point instead of 16 as two doubles.
    polyline    text             NOT NULL,
    distance_m  double precision NOT NULL CHECK (distance_m >= 0),
    duration_s  double precision NOT NULL CHECK (duration_s >= 0),
    created_at  timestamptz      NOT NULL,
    PRIMARY KEY (trip_id, kind, version)
);
