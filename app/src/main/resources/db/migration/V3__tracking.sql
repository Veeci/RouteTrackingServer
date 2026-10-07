-- V3: the tracking context. Only tracking's adapters read or write these tables; other contexts use
-- its events and its public API.

CREATE TABLE tracking_sessions (
    id           uuid        PRIMARY KEY,
    device_id    text        NOT NULL,
    sdk_version  text        NOT NULL,
    started_at   timestamptz NOT NULL,
    last_seen_at timestamptz NOT NULL
);

-- One row per stored batch. The primary key is the idempotency guarantee: a second insert of the same
-- (session_id, seq) does nothing, even when two requests race.
CREATE TABLE fix_batches (
    session_id     uuid        NOT NULL REFERENCES tracking_sessions (id),
    seq            bigint      NOT NULL CHECK (seq > 0),
    received_at    timestamptz NOT NULL,
    accepted_count int         NOT NULL,
    rejections     jsonb       NOT NULL DEFAULT '[]',
    PRIMARY KEY (session_id, seq)
);

-- One row per accepted fix. Coordinates and computed distances are double precision. Values the device
-- measured (accuracy, speed, bearing, altitude) are real: 7 significant digits are far more than their precision.
CREATE TABLE fixes (
    session_id           uuid             NOT NULL,
    seq                  bigint           NOT NULL,
    idx                  int              NOT NULL,
    lat                  double precision NOT NULL,
    lng                  double precision NOT NULL,
    accuracy_m           real             NOT NULL,
    speed_mps            real,
    speed_accuracy_mps   real,
    bearing_deg          real,
    bearing_accuracy_deg real,
    altitude_m           real,
    vertical_accuracy_m  real,
    recorded_at          timestamptz      NOT NULL,
    provider             text             NOT NULL,
    mock                 boolean          NOT NULL DEFAULT false,
    sat_used             smallint,
    sat_mean_cn0_dbhz    real,
    distance_from_prev_m double precision NOT NULL,
    cumulative_m         double precision NOT NULL,
    derived_speed_mps    real,
    PRIMARY KEY (session_id, seq, idx),
    FOREIGN KEY (session_id, seq) REFERENCES fix_batches (session_id, seq)
);

-- "The fixes of session X between t1 and t2" and "the latest fix of session X before t".
CREATE INDEX fixes_session_time ON fixes (session_id, recorded_at);
