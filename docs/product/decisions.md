# Product decisions

Every business rule the server applies today, where it came from, and the questions nobody has answered
yet. Written on 2026-11-12, with `main` at `d0c2b15` (phases 0 to 3 merged).

The Source column says who decided:

| Source | Meaning |
|---|---|
| Plan | Written in the phase plan before the code |
| Built P*n* | Decided while building phase *n*, recorded in that phase's "As built" section |
| Fix | Decided while fixing a bug (PR number given) |
| Proposed | Phase 4 proposal, not built yet |
| Gap | Nobody decided it. The code allows it because nothing forbids it |

Rows marked **Open** need a product decision.

## What the product does today

A guest books a trip. A driver accepts it and drives. The guest watches the driver's position, the distance
and the ETA live.

Not built yet: road routing (phase 6), arriving, stale and low-battery notices (4), remote SDK commands (5),
history and export (8), SDK health reports (9), several server instances (10), the admin dashboard (11) and a
staging server (1.5).

Not in any plan: payments and pricing, automatic driver matching, ratings, push notifications while the app
is closed, and sign-up and login for real users.

## Trips

| # | Problem | What happens now | Source |
|---|---|---|---|
| 1 | Who may book a trip | Only a guest | Plan |
| 2 | How a driver gets a trip | A driver lists every open trip and accepts one. There is no filter by distance. **Open** | Plan |
| 3 | Two drivers accept the same trip at once | The first one wins. The second gets 409 ILLEGAL_TRANSITION, so they learn the trip is taken | Built P3 |
| 4 | A driver with two trips | Not allowed. The service checks it, and a unique index enforces one active trip per driver | Plan |
| 5 | A guest with two trips | Allowed, with no limit. **Open** | Gap |
| 6 | A trip nobody accepts | It stays REQUESTED forever. **Open** | Gap |
| 7 | Trip stages | REQUESTED, ACCEPTED, ARRIVED, IN_PROGRESS, then COMPLETED or CANCELLED | Plan |
| 8 | Who may cancel, and until when | The guest or the assigned driver, until ARRIVED. A started ride cannot be cancelled. There are no reasons, no fees and no "guest did not show up" stage. **Open** | Plan |
| 9 | Who moves a trip forward | Only the assigned driver (arrive, start, complete). An admin can read every trip but cannot act on one. **Open** | Plan |
| 10 | Someone guesses another person's trip id | They get TRIP_NOT_FOUND, the same answer as for a trip that does not exist | Plan |
| 11 | A guest taps "book" twice | The `Idempotency-Key` header makes the second request return the first trip | Plan |
| 12 | Two changes to one trip at once | The second change gets 412 VERSION_MISMATCH and must read the trip again | Built P3 |

## The driver's location data

| # | Problem | What happens now | Source |
|---|---|---|---|
| 13 | The phone loses its connection | The phone numbers each batch and keeps it until the server acks it. The server acks only after the commit, and a resent batch is stored once. No point is lost or counted twice | Plan |
| 14 | Fake-GPS apps | Fixes marked as mock are rejected (MOCK_LOCATION). **Open**: is this the right policy | Plan |
| 15 | Bad fixes | Rejected but still acked: accuracy worse than 50 m, a time more than 30 s ahead or more than 24 h old, a repeated time, or a jump faster than 70 m/s (252 km/h) | Plan, Built P2 |
| 16 | A cell-tower fix 1.5 km off | Counted as imprecise (POOR_ACCURACY), not as invalid | Built P2 |
| 17 | The first fix of a session is wrong | Later correct fixes look like jumps and are rejected. A reset after several rejections in a row would fix it. Known limit | Built P2 |
| 18 | Another phone reuses a driver's session | Refused with SESSION_DEVICE_MISMATCH | Built P2 |
| 19 | GMS phones compared with AOSP phones | The SDK does not yet say which location stack made a fix, so the server stores UNKNOWN. The phase 9 report needs an SDK change | Built P2 |
| 20 | Limits | 100 fixes per batch, 20 messages per second per socket, 64 KiB per frame, 300 HTTP requests per minute per IP. Behind a proxy every client shares one IP until phase 1.5. **Open** | Plan |

## What the guest sees

| # | Problem | What happens now | Source |
|---|---|---|---|
| 21 | Distance to the driver | A straight line until phase 7 measures it along the road. **Open**: acceptable until then | Plan |
| 22 | ETA while the driver stands still | Speed is a moving average and never below 2 m/s, so the ETA stays finite | Plan |
| 23 | A guest with a slow connection | Old positions are dropped. The latest position matters, and at most 64 updates wait per trip | Plan |
| 24 | Stages that change quickly | The server reads the trip again before each status message, so the guest always sees the current stage. A stage in between may be skipped | Built P3 |
| 25 | The trip ends | The guest gets the final stage, then the socket closes with 1000 | Plan |
| 26 | The ride starts while a batch is still on its way | The progress target switches to the destination. A late batch can show a destination distance right after a pickup distance | Fix #14 |

## Accounts and security

| # | Problem | What happens now | Source |
|---|---|---|---|
| 27 | How users log in | There is no real login. `POST /api/v1/dev/tokens` creates a user of any role with no password, and exists only in dev and test. Production login is not designed. **Open** | Built P3 |
| 28 | Token signing | JWT with HMAC-SHA256 and `JWT_SECRET`. The server does not start when the secret is missing or short | Built P3 |
| 29 | A guest connects to the driver socket | The socket closes with 4403 | Built P3 |

## Problems found and fixed on 2026-11-09

| # | Problem | Fix | PR |
|---|---|---|---|
| 30 | An oversized upload without Content-Length sometimes got 400 MALFORMED_REQUEST instead of 413 PAYLOAD_TOO_LARGE, so a client could react the wrong way | The error handler looks through the cause chain | #12 |
| 31 | On shutdown the database pool closed while the outbox relay and live sockets still used it. Every deploy logged errors, and a guest could miss an update | Shutdown now stops in order: readiness DOWN, outbox relay, sockets (handler and connection), then the pool | #13, #15 |
| 32 | The socket wait (up to 2 s) and Netty's shutdown timeout (8 s) add up to 10 s, which is when Docker kills the process. **Open**: lower `shutdownTimeout` to 7 s | Not changed | |
| 33 | Flaky tests blocked releases and hid real bugs | Fixed. Socket tests run on Netty, as in production | #12 to #15 |
| 34 | 21 library updates (Ktor 3.6.1, Gradle 9.8.2) | Merged. Gradle 10 deprecation warnings remain in the build scripts | #10 |

## Phase 4 (4a to 4c built)

| # | Problem | Proposal | Source |
|---|---|---|---|
| 35 | Marking "driver arrived" without the driver | The server moves the trip to ARRIVED when the driver is within 30 m for 3 fixes in a row, or within 30 m and slower than 1.5 m/s for 10 s. A driver parked next door could be marked arrived, and ARRIVED cannot be undone. **Open** | Built P4 (4a) |
| 36 | When the guest sees "arriving" | Within 200 m for 2 fixes in a row. It resets only beyond 300 m, so GPS noise cannot make it flicker | Built P4 (4a) |
| 37 | When the driver counts as gone | After 60 s with no frame from the phone. The SDK must send `device_status` every 30 s, also while parked. Aggressive OEM battery savers will cause real "connection lost" notices. "Back" shows within 10 s | Built P4 (4c) |
| 38 | When the battery counts as low | At 15 % or below and not charging. It resets at 25 % or when charging. A CRITICAL profile is low at any level | Built P4 (4b) |
| 39 | Older app versions | New notices reuse the `trip_event` message and keep `status`, so an older SDK ignores what it does not know. The SDK must ignore unknown `event` values | Built P4 (4a) |
| 40 | Showing whether "arrived" was automatic | Stored as `cause` for history, not sent to the guest | Built P4 (4a) |

## Open questions, most important first

1. Login: who issues tokens in production, and how do guests and drivers sign up (row 27)?
2. Dispatch: should drivers see every open trip, only nearby ones, or should the server assign trips (row 2)?
3. May a guest have several trips at once (row 5)?
4. After how long does an unaccepted trip expire (row 6)?
5. May a started ride be cancelled, and is there a "guest did not show up" stage (row 8)?
6. May an admin cancel or reassign a trip (row 9)?
7. Do we want auto-arrive, and may the driver undo it (row 35)?
8. Is rejecting every mock fix the right policy (row 14)?
9. Is a straight-line ETA acceptable until phase 7 (row 21)?
10. How long do we keep drivers' location history? Phase 10 plans retention but sets no number.
11. Lower `shutdownTimeout` to 7 s (row 32)?
12. Is one shared IP behind a proxy acceptable until phase 1.5 (row 20)?
