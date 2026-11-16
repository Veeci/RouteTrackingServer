-- A user's trip history, newest first, paged by (created_at, id) (phase 8b): TripRepository.findHistory.
-- One index per side of the trip. Each query matches one user on one column, so Postgres reads one range of one
-- index in the order of the ORDER BY and stops after the page: no sort step, and the cost of a page does not grow
-- with the number of trips.
CREATE INDEX trips_guest_history ON trips (guest_id, created_at DESC, id DESC);

-- Partial: a trip nobody accepted has no driver and never shows in a driver's history. `driver_id = ?` implies
-- `driver_id IS NOT NULL`, so the planner may use this index for the driver query.
CREATE INDEX trips_driver_history ON trips (driver_id, created_at DESC, id DESC) WHERE driver_id IS NOT NULL;
