-- Session health (phase 9b). Owned by the telemetry context, which reads tracking and device control through
-- their APIs only, so there is no foreign key to tracking_sessions.

-- The health job pages through idle sessions in (last_seen_at, id) order (SessionTelemetry.idleSessions).
CREATE INDEX tracking_sessions_last_seen ON tracking_sessions (last_seen_at, id);

-- One row per computed session; computing it again replaces the row. The metrics are one JSON document; the
-- columns beside it are what the provider report (9c) groups and filters by.
CREATE TABLE session_health (
    session_id           uuid        PRIMARY KEY,
    driver_id            uuid,
    started_at           timestamptz NOT NULL,
    session_last_seen_at timestamptz NOT NULL,
    computed_at          timestamptz NOT NULL,
    primary_provider     text,
    manufacturer         text,
    model                text,
    android_sdk          int,
    rom                  text,
    has_gms              boolean,
    metrics              jsonb       NOT NULL
);

-- The report's time range filters on the session's start.
CREATE INDEX session_health_started ON session_health (started_at);

-- Where the health job stopped: the (last_seen_at, session_id) of the last session it computed. One row at most.
-- Its own table, not max() over session_health: an admin's recompute of a live session must not move it.
CREATE TABLE session_health_watermark (
    id           smallint    PRIMARY KEY CHECK (id = 1),
    last_seen_at timestamptz NOT NULL,
    session_id   uuid        NOT NULL
);
