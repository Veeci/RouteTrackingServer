# Product Features

## Product in one sentence

A guest books a trip, sees a suggested shortest route, and watches the driver approach live with
distance-left and ETA; the server pushes trip events to both sides and can tune the driver SDK's
tracking policy remotely.

Rule of thumb: **WebSocket for anything live** (fixes in, events and commands out, live viewers).
**REST for anything historical or transactional** (trips, history, summaries, exports, reports).

## End-user features

| # | Feature | What the user gets | User | Type | Phase |
|---|---|---|---|---|---|
| U1 | Book a trip | Pick pickup and destination, create the trip | Guest | Data – CRUD | 3 |
| U2 | Accept / cancel trip | Driver accepts an assigned trip; either side can cancel | Both | Data – CRUD | 3 |
| U3 | Suggested route | Shortest route pickup → destination, drawn on the map | Both | Processing – Algorithm | 6 |
| U4 | Live driver location | Driver's car moves on the map in real time, rotated to bearing | Guest | Realtime | 3 |
| U5 | Distance left & ETA | "Driver is 1.2 km away · 4 min", measured along the road | Guest | Realtime + Processing | 3 (straight line), 7 (along route) |
| U6 | Trip status updates | *Assigned*, *Arriving*, *Arrived*, *Started*, *Completed* | Both | Realtime | 4 |
| U7 | Automatic re-route | Off-route driver gets a new route; both sides see it | Both | Realtime + Processing | 7 |
| U8 | Driver connection status | "Connection lost · last seen 1 min ago" | Guest | Realtime | 4 |
| U9 | Low-battery warning | "Driver's phone battery is low" | Guest | Realtime | 4 |
| U10 | Trip summary | Distance, duration, avg/max speed, route map | Both | Processing | 8 |
| U11 | Trip history | List past trips, open one | Both | Data – CRUD | 8 |
| U12 | Trip replay | Play back a past trip | Both | Data – CRUD | 8 |
| U13 | Export trip | Download GPX / GeoJSON | Both | Data – CRUD | 8 |

## Internal features

| # | Feature | Short description | Type | Phase |
|---|---|---|---|---|
| I1 | Driver fix ingest | Batched fixes with seq; ACK after persist; idempotent writes | Realtime | 2 |
| I2 | Heartbeat & presence | Ping/pong keepalive; online/last-seen tracking | Realtime | 1 (ping), 4 (presence) |
| I3 | Fix processing pipeline | Validate → dedup → jump filter → enrich | Processing | 2 |
| I4 | Trip state machine | Legal transitions in the trip aggregate, hysteresis for noisy triggers | Processing | 3, 4 |
| I5 | Route snapping | Project driver onto route polyline, distance along it | Processing | 7 |
| I6 | ETA calculation | Remaining distance ÷ smoothed speed | Processing | 3, 7 |
| I7 | Off-route detection | > 50 m from route for N fixes → re-route | Processing | 7 |
| I8 | Nearest-node lookup | lat/lng → closest graph node (grid index) | Algorithm | 6 |
| I9 | Remote SDK commands | `SET_CONSTRAINT`, `REGISTER_GEOFENCES`, `FLUSH_QUEUE` | Realtime | 5 |
| I10 | Auth & access control | Token → principal {user, role}; guests see only their trip | Security | 3 |
| I11 | SDK health telemetry | Battery drain, accuracy, dropouts, Doze gaps per session | Data | 9 |
| I12 | GMS vs AOSP report | Compare fix quality and battery between stacks | Analytics | 9 |
| I13 | Driver simulator | Replays a recorded route through the driver socket | Dev tooling | 2 |
| I14 | Horizontal scale | Multi-instance live fan-out, presence and command routing via Redis | Platform | 10 |
| I15 | Delivery & operations | Container image, deploy pipeline, dashboards, alerts, backups, retention | Platform | 0, 1, 10 |
| I16 | Admin API & live fleet | Fleet-wide driver list, live map channel, trip oversight, audit log for the admin dashboard | Data + Realtime | 11 |

## What the SDK already produces

Based on `RouteTrackingSdk/location_sdk` as of 2026-09-30.

| SDK capability | Data | BE usage |
|---|---|---|
| `FusedLocationSource` / `AospLocationManagerService` | Fixes from 2 stacks | Core ingest; `provider` field enables I12 |
| `FusedOrientationProvider`, `VirtualOrientationModule` | Bearing | U4 marker rotation; direction check in I5 |
| `ActivityTransitionDetector`, `MotionHeuristicEngine` | Still / walking / driving | Ignore drift while stationary; auto start/stop hints |
| `GeofencingSource`, `ProximityAlertManager` | Enter / exit / dwell | Server pushes pickup/destination geofences (I9) |
| `GNSSStatusManager` → `SatelliteHealth` | Satellites used, SNR | Trust weight in I3; I12 report |
| `BatteryModule` → `BatteryReading` / `BatteryProfile` | Level, temp, profile | U9; I11 |
| `TrackingPolicyEngine` + `TrackingConstraint.mergeRestrictive` | Accuracy / interval | I9: the server is one more constraint source (`RemoteModule` in the SDK) |
