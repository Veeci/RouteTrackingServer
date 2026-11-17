-- Progress state per active trip (phase 10): the smoothed speed, the last fix and the driver's place on the route.
-- It was in each instance's memory. With several instances, the events of one trip may be handled on any of
-- them, so the state lives here, written in the outbox relay's transaction together with the event it follows
-- from. The route's polyline is not repeated: it is in trip_routes, and each instance keeps decoded lines in a cache.
CREATE TABLE trip_progress_state (
    trip_id    uuid        PRIMARY KEY REFERENCES trips (id),
    state      jsonb       NOT NULL,
    updated_at timestamptz NOT NULL
);
