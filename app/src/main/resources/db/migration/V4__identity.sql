-- Users (identity context). In phase 3 only the dev token endpoint creates them (dev and test profiles).
-- Production will take users from an external identity provider and keep this table as its local copy.
CREATE TABLE users (
    id           uuid        PRIMARY KEY,
    display_name text        NOT NULL,
    role         text        NOT NULL CHECK (role IN ('GUEST', 'DRIVER', 'ADMIN')),
    created_at   timestamptz NOT NULL DEFAULT now()
);
