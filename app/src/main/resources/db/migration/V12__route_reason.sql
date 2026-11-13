-- Why a trip route was planned (phase 7b). INITIAL: when the trip was booked (TRIP) or the driver set off
-- (APPROACH, phase 7c). OFF_ROUTE: the driver left the previous version. The default keeps inserts from the
-- previous release (which do not know the column) valid during a rolling deploy.
ALTER TABLE trip_routes
    ADD COLUMN reason text NOT NULL DEFAULT 'INITIAL' CHECK (reason IN ('INITIAL', 'OFF_ROUTE'));
