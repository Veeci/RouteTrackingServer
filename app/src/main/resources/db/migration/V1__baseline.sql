-- V1: baseline. Proves the migration pipeline end to end; business tables arrive with their phases.
-- Rule: a merged migration is never edited. Changes go in a new V<n+1> file.

CREATE TABLE schema_info (
    key        text        PRIMARY KEY,
    value      text        NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now()
);

INSERT INTO schema_info (key, value) VALUES ('baseline', 'phase-1');
