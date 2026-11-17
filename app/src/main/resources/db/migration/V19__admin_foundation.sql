-- Phase 11a: the OPERATOR role, disabling drivers, and the audit log.

ALTER TABLE users DROP CONSTRAINT users_role_check;
ALTER TABLE users ADD CONSTRAINT users_role_check CHECK (role IN ('GUEST', 'DRIVER', 'OPERATOR', 'ADMIN'));

-- A disabled driver cannot open a tracking session. Null: enabled.
ALTER TABLE users ADD COLUMN disabled_at timestamptz;
ALTER TABLE users ADD COLUMN disabled_reason text;

-- Who did what to what, for every admin action. Written by the service in the same transaction as the action,
-- so an action cannot commit without its row.
CREATE TABLE audit_log (
    id          bigserial   PRIMARY KEY,
    at          timestamptz NOT NULL,
    actor_id    uuid        NOT NULL,
    actor_role  text        NOT NULL,
    action      text        NOT NULL,
    target_type text        NOT NULL,
    target_id   text        NOT NULL,
    reason      text,
    details     jsonb       NOT NULL DEFAULT '{}',
    request_id  text
);

-- "What happened to this driver (or trip, or session)", newest first.
CREATE INDEX audit_log_target ON audit_log (target_type, target_id, id DESC);
-- "What did this person do", newest first.
CREATE INDEX audit_log_actor ON audit_log (actor_id, id DESC);

-- Append-only. Not with grants: the app's user owns the table (it runs Flyway too), and an owner can grant itself
-- any right back. A trigger runs for every role, the owner included. TRUNCATE is refused the same way.
CREATE FUNCTION audit_log_append_only() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'audit_log is append-only: % is not allowed', TG_OP;
END;
$$;

CREATE TRIGGER audit_log_no_update_or_delete
    BEFORE UPDATE OR DELETE ON audit_log
    FOR EACH ROW EXECUTE FUNCTION audit_log_append_only();

CREATE TRIGGER audit_log_no_truncate
    BEFORE TRUNCATE ON audit_log
    FOR EACH STATEMENT EXECUTE FUNCTION audit_log_append_only();
