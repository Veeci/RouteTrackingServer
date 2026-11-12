-- Presence (phase 4): the latest last_seen_at of each driver's sessions, for the stale-driver job
-- (DriverPresence.lastSeen). The index answers it without reading the table.
CREATE INDEX tracking_sessions_driver_last_seen ON tracking_sessions (driver_id, last_seen_at);
