-- Phase 11d: the staff trip list (GET /api/v1/admin/trips) pages every trip newest first, by (created_at DESC, id DESC).
-- The status, guest and driver filters already have indexes, but the unfiltered list (the dashboard's first page)
-- and a `from`/`to` range had none. EXPLAIN ANALYZE over 200 000 trips showed a parallel sequential scan with a top-N
-- sort, about 18 ms a page; with this index it is an index scan of 51 rows, under 0.1 ms. Numbers in
-- docs/plan/phase-11-admin.md, "As built (11d)".

CREATE INDEX trips_created ON trips (created_at DESC, id DESC);
