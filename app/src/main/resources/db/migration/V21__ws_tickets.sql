-- Phase 11c: single-use tickets for the admin fleet socket. A browser cannot set an Authorization header on a
-- WebSocket, so it trades its access token for a ticket over REST and puts the ticket in the socket URL. Only the
-- SHA-256 of the ticket is stored: a leaked row or backup holds nothing a client could connect with.
-- Redeemed with DELETE ... RETURNING, so a ticket works once, on whichever instance the socket reaches.

CREATE TABLE ws_tickets (
    hash       bytea       PRIMARY KEY,
    user_id    uuid        NOT NULL,
    role       text        NOT NULL,
    expires_at timestamptz NOT NULL
);

-- The retention job deletes tickets nobody redeemed.
CREATE INDEX ws_tickets_expires ON ws_tickets (expires_at);
