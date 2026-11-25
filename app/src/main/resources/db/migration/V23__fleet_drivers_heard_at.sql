-- Fleet lag fix C: judge "online" on the server's clock. heard_at is the server time when ingest last accepted data
-- from the driver (a hello, a stored batch or a device_status), the same moments that move
-- tracking_sessions.last_seen_at. The fleet context writes it from the ingest path, at most once per flush per driver,
-- so a relay backlog or a slow phone clock no longer hides a driver. last_seen_at keeps the phone's time of the newest
-- position or status, and the driver list stays ordered by it.
ALTER TABLE fleet_drivers ADD COLUMN heard_at timestamptz;

-- "Online" is heard_at > now - staleAfter: the online filter, the counts, and the live tick's stale window
-- (heard_at BETWEEN), which joins fleet_drivers_updated in a BitmapOr.
CREATE INDEX fleet_drivers_heard ON fleet_drivers (heard_at);

-- Start from what tracking already knows, so a driver online at deploy time stays online. The V9 index answers the
-- subquery without reading tracking_sessions.
UPDATE fleet_drivers f
SET heard_at = s.heard_at
FROM (
    SELECT driver_id, max(last_seen_at) AS heard_at
    FROM tracking_sessions
    WHERE driver_id IS NOT NULL
    GROUP BY driver_id
) s
WHERE s.driver_id = f.driver_id;
