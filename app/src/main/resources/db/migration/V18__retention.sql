-- Retention of raw location data (phase 10). When a session's GPS fixes and status samples are deleted, the
-- session itself stays, marked with purged_at, so its trips' summaries and its health keep their source.

ALTER TABLE tracking_sessions ADD COLUMN purged_at timestamptz;

-- "Sessions not purged yet, last seen before the cutoff, oldest first": the retention job's only query. Partial,
-- so the years of purged sessions are not in it and the job never walks past them.
CREATE INDEX tracking_sessions_unpurged ON tracking_sessions (last_seen_at, id) WHERE purged_at IS NULL;
