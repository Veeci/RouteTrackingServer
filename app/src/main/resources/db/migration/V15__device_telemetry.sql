-- Device telemetry (phase 9a). The device a session runs on, from the optional `device` block of hello, and every
-- device_status sample, so that session health (phase 9b) can compare GMS and AOSP stacks and spot Doze gaps.

-- All nullable: an older SDK sends no device block, and a newer one may send only some fields.
ALTER TABLE tracking_sessions
    ADD COLUMN device_manufacturer text,
    ADD COLUMN device_model        text,
    ADD COLUMN device_android_sdk  int CHECK (device_android_sdk >= 1),
    ADD COLUMN device_rom          text,
    ADD COLUMN device_has_gms      boolean;

-- One row per device_status message. The key is the idempotency guarantee: a device that sends the same sample
-- again after a reconnect writes nothing the second time. Only tracking writes it; telemetry reads it through
-- tracking's API, never this table.
CREATE TABLE device_status_samples (
    session_id    uuid        NOT NULL REFERENCES tracking_sessions (id),
    at            timestamptz NOT NULL,
    level_percent smallint    NOT NULL CHECK (level_percent BETWEEN 0 AND 100),
    is_charging   boolean     NOT NULL,
    temperature_c double precision, -- not real: 38.7 must read back as 38.7, not 38.70000076
    profile       text,
    motion        text,
    PRIMARY KEY (session_id, at)
);
