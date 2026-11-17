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
| 21 | Distance to the driver | Along the planned road (phase 7). Before the ride, from the second fix after accept: the first fix plans the driver's route to the pickup. Until then, and when no route can be planned, a straight line | Built P7 (7a, 7c) |
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

## Phase 5 (built)

| # | Problem | What happens now | Source |
|---|---|---|---|
| 41 | Who a device command is for | The driver, not one tracking session, so it reaches whichever session the driver opens next | Built P5 (5a) |
| 42 | Who may send commands by hand | Only an admin. The server's own policies (5c) need no user | Built P5 (5a) |
| 43 | How long a command waits | 10 minutes without an ack, then it expires and is never sent | Built P5 (5a) |
| 44 | Two accuracy commands in a row | The newer one cancels the older one if it was not sent yet | Built P5 (5a) |

| 45 | Saving the driver's battery when nobody watches | High accuracy (1 s) while a guest watches, balanced (5 s) when the last guest leaves before the ride, passive (60 s) after the trip | Built P5 (5c) |
| 46 | Waking the phone near the pickup and destination | A 150 m geofence at the pickup when the trip is accepted, at the destination when the ride starts. The server still decides "arrived" from GPS | Built P5 (5c) |

## Phase 7 (built)

| # | Problem | What happens now | Source |
|---|---|---|---|
| 47 | The ETA along a road | The route's planned time for the distance left, scaled by how fast the driver really drives. A driver stopped at a red light makes it at most 3 times the plan, a fast one at least half. The route stores only its total time, so a fast road ahead and a slow alley count the same | Built P7 (7a) |
| 48 | "Arriving" and the road distance | "Arriving" and the automatic ARRIVED still use the straight line. The guest can see "arriving" (200 m straight) while the progress still shows 350 m by road. Kept on purpose: ARRIVED cannot be undone, and a bad snap onto the route must never cause it | Built P7 (7a) |
| 49 | When the driver counts as off the route | 3 fixes in a row more than 50 m beside the route, each accurate to 30 m or better. A fix within 30 m resets the count, and between 30 and 50 m nothing changes. Imprecise fixes (a tunnel, tall buildings) never count | Built P7 (7b) |
| 50 | How often the server re-plans | At most once every 30 s per trip. The guest gets `off_route`, then the new line. When no route can be found (no road near the driver), the old line stays and the server tries again after 30 s | Built P7 (7b) |
| 51 | When the route to the pickup is planned | From the driver's first fix after accepting, not at the moment of accept: the server has no position before that. A failed plan (driver far from any road) is tried again every 30 s | Built P7 (7c) |
| 52 | What the driver's app gets | The same `route_updated` as the guest: the route to the pickup, the trip route when the ride starts, and each re-route. It is sent again after every reconnect, so the app can draw it and later give directions | Built P7 (7c) |

## Phase 8 (built, decided 2026-11-16)

| # | Problem | What will happen | Source |
|---|---|---|---|
| 53 | Summary of a cancelled trip | None. The trip shows in the history with its stage times only | Plan |
| 54 | Who sees where the driver drove before the pickup | Only the driver and admins. The guest sees the ride leg only | Plan |
| 55 | A phone offline for a while during the ride | A gap over 60 s between points adds no distance and no moving time | Plan |
| 56 | History and exports of a running trip | Refused with 409 TRIP_NOT_ENDED. The live socket covers a running trip | Plan |
| 57 | A batch from the ride that arrives after "complete" | Stored, but missing from the summary, which is computed at completion. An admin can compute it again. Known limit | Built P8 (8a) |
| 58 | Whose trips "my trips" lists | The token decides: a guest gets the trips they booked, a driver the trips they accepted. There is no parameter for another user or side. An admin gets 403, because a list of every trip belongs to the admin dashboard (phase 11) | Built P8 (8b) |
| 59 | What "my trips" holds | Every stage, newest first, running and cancelled trips included, 20 per page (at most 100). An item has the stage times and, once summarized, the ride distance, never the track | Built P8 (8b) |
| 60 | Who sees the approach in a trip's track | The driver and admins. A guest who asks gets 403 TRIP_ACTION_FORBIDDEN, not 404: the guest knows the trip exists | Built P8 (8c) |
| 61 | What a track's timeline holds | The whole trip, whatever leg is asked: stages, moments and route versions, in time order, with the guest socket's names. Never simplified | Built P8 (8c) |
| 62 | Where a timeline entry sits on the GeoJSON map | At the driver's last fix at or before its time, inside the leg including its end. With no such fix, the point has no geometry, never a guessed position | Built P8 (8d) |
| 63 | What the exports hold | One leg each, with the same access rules as the track. GPX holds the points and their times only; GeoJSON adds the timeline as points | Built P8 (8d) |

## Phase 9 (built, decided 2026-11-16)

| # | Problem | What will happen | Source |
|---|---|---|---|
| 64 | A phone that sends no device block | It still connects. Its session has no device, and every device column stays null | Built P9 (9a) |
| 65 | A reconnect whose hello has no device block | The stored device stays. Only a hello with a block replaces it | Built P9 (9a) |
| 66 | The same device status sent twice | Stored once, keyed by session and reading time. The event is still announced again, as before phase 9 | Built P9 (9a) |
| 67 | When a session's health is computed | 10 minutes after its last message, by a job every 5 minutes, and again if the session resumes. There is no "session closed" signal | Plan (corrected) |
| 68 | Who sees a session's health | Admins only. A driver gets 403, even for their own session | Built P9 (9b) |
| 69 | Which fix interval a gap is measured against | The SET_CONSTRAINT the device had applied when the gap started, counted from its ack. It holds across sessions until the next one. Without one, 5 s | Built P9 (9b) |
| 70 | How battery drain is reported | Percent per hour while not charging, positive when the battery runs down. A stretch under 10 minutes or with one sample is left out | Built P9 (9b) |
| 71 | What the provider report compares | Sessions that started in a range of at most 31 days, grouped by primary provider, GMS, ROM and Android SDK. Each value is a median over the group. A group under 3 sessions is marked low sample | Built P9 (9c) |
| 72 | How gaps are compared across groups | Per tracked hour, so long sessions are not penalised for their length | Built P9 (9c) |
| 73 | Who may download the report | Admins only, as JSON or CSV | Built P9 (9c) |

## Phase 10 (10a to 10c built, decided 2026-11-17)

| # | Problem | What will happen | Source |
|---|---|---|---|
| 74 | Redis is down while instances run | Fixes are stored and acked, trips change stage, commands wait for the next reconnect. Guests see no live updates until Redis is back. The instances stay ready | Built P10 (10a) |
| 75 | Two guests start watching at the same moment on two instances | Both may count as first, so the driver's device gets the same precision command twice and applies it once | Built P10 (10a) |
| 76 | Events of one trip on several instances | One instance handles events at a time, oldest first, so a trip's events stay in order | Built P10 (10a) |
| 77 | A guest reconnects after a deploy | They get the trip's latest update again, then new ones. The app treats a `trip_event` with the same kind and time as the one it already showed | Built P10 (10b) |
| 78 | How long raw GPS points and battery readings are kept | 90 days after the session's last message. Trips, summaries, moments, routes and session health are kept | Built P10 (10c) |
| 79 | The track of a trip older than 90 days | The timeline and the summary stay. The points are gone: the track says `pointsExpired`, and exports and summary recompute answer 410 TRACK_EXPIRED | Built P10 (10c) |
| 80 | Where alerts go | A Discord channel, through a webhook URL kept out of git. Without one, alerts show in Prometheus only | Built P10 (10c) |

## Phase 11 (11a built, decided 2026-11-17)

| # | Problem | What will happen | Source |
|---|---|---|---|
| 81 | Who runs the fleet day to day | OPERATOR: sees every trip, driver and track. ADMIN also disables drivers, sends commands, recomputes, and reads the audit log | Built P11 (11a) |
| 82 | How long a staff token lasts | 15 minutes, against 12 hours for guests and drivers, because it can do more | Built P11 (11a) |
| 83 | A disabled driver | Their sockets close at once on every instance, and a new session is refused. Their tokens still work for REST until they expire. A reason is required and kept | Built P11 (11a) |
| 84 | What the audit log keeps | Every admin action with who, when, the target, the reason and the request id. It is never changed or deleted | Built P11 (11a) |

## Open questions, most important first

1. Login: who issues tokens in production, and how do guests and drivers sign up (row 27)?
2. Dispatch: should drivers see every open trip, only nearby ones, or should the server assign trips (row 2)?
3. May a guest have several trips at once (row 5)?
4. After how long does an unaccepted trip expire (row 6)?
5. May a started ride be cancelled, and is there a "guest did not show up" stage (row 8)?
6. May an admin cancel or reassign a trip (row 9)?
7. Do we want auto-arrive, and may the driver undo it (row 35)?
8. Is rejecting every mock fix the right policy (row 14)?
9. How long do we keep drivers' location history? Phase 10 plans retention but sets no number.
10. Lower `shutdownTimeout` to 7 s (row 32)?
11. Is one shared IP behind a proxy acceptable until phase 1.5 (row 20)?
