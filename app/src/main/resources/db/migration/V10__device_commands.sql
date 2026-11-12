-- Commands for drivers' devices (device control, phase 5). One row per command; it waits for the device's ack
-- or expires. `payload` is the command's content as JSON, with its kind in `kind`. Addressed to the driver
-- (not a tracking session), because the SDK starts a new session each time tracking starts.
CREATE TABLE device_commands (
    id         uuid        PRIMARY KEY,
    driver_id  uuid        NOT NULL,
    kind       text        NOT NULL CHECK (kind IN ('SET_CONSTRAINT', 'REGISTER_GEOFENCES', 'FLUSH_QUEUE')),
    payload    jsonb       NOT NULL,
    status     text        NOT NULL CHECK (status IN ('PENDING', 'SENT', 'APPLIED', 'REJECTED', 'EXPIRED')),
    created_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL
);

-- A driver's commands by status, oldest first: delivery after a reconnect, the admin list.
CREATE INDEX device_commands_driver ON device_commands (driver_id, status, created_at);

-- Open commands by expiry time, for the expiry job. Partial: closed commands are never looked at again.
CREATE INDEX device_commands_due ON device_commands (expires_at) WHERE status IN ('PENDING', 'SENT');
