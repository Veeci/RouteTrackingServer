-- The driver (a user id from the identity context) who opened a tracking session. The trip context uses it
-- to find the driver's active trip. Sessions stored before phase 3 have no driver. There is no foreign key
-- to users: a context never ties its tables to another context's tables (docs/architecture/database.md).
ALTER TABLE tracking_sessions ADD COLUMN driver_id uuid;
