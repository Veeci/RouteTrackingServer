# Phase 9 — SDK Health Telemetry & GMS vs AOSP Report

Branch: `feat/p9-telemetry` · Depends on: 2 (4 for device status) · Features: I11, I12

## Goal

Turn the backend into a testbench for the SDK: record how each tracking session actually behaved
(fix cadence, accuracy, gaps, battery drain) and compare location stacks (GMS fused vs AOSP
providers) across devices and ROMs. This answers questions the phone alone can't: "does the AOSP path
on my custom ROM drop more fixes in Doze?".

## Scope

**In:** device descriptor in `hello`, persisted device status samples, per-session health metrics,
gap/dropout detection, battery drain rate, provider comparison report (JSON + CSV), admin endpoints.

**Out:** dashboards/UI (export CSV and chart elsewhere), crash reporting.

## Design

### Protocol additions

`hello` gains an optional `device` block (backward compatible):

```json
"device": { "manufacturer": "Google", "model": "Pixel 7", "androidSdk": 35, "rom": "LineageOS 22.1", "hasGms": false }
```

`device_status` (phase 4) samples are now persisted: `V10__telemetry.sql` → `device_status_samples(session_id, at, level_percent, is_charging, temperature_c, profile, motion)`,
and `tracking_sessions` gains the device columns.

### Metrics (`telemetry/domain`)

`SessionHealthCalculator.calculate(fixes, rejections, samples, session): SessionHealth`

| Metric | Definition |
|---|---|
| `fixCount`, `acceptedRatio` | accepted / (accepted + rejected) |
| `rejectionsByReason` | counts per `RejectionReason` |
| `intervalP50S`, `intervalP95S` | percentiles of time between consecutive accepted fixes |
| `accuracyP50M`, `accuracyP95M` | percentiles of `accuracyM` |
| `gaps` | intervals > `max(3 × expectedIntervalS, 30 s)`; each with start, duration, `likelyDoze` flag |
| `likelyDoze` | gap where the sample before and after shows `isCharging = false` and `motion = STILL`, duration ≥ 5 min |
| `batteryDrainPctPerHour` | linear regression slope of `levelPercent` over time, excluding charging intervals |
| `providerMix` | share of fixes per `provider` |

`expectedIntervalS` comes from the last applied `SetConstraint` command (phase 5) if present, else config default.

Computed by `SessionHealthListener` on `SessionClosed` (published by `tracking` when a driver socket closes) and on demand via admin endpoint; stored in `session_health` (jsonb metrics + key columns for grouping).

### Report

`ProviderComparisonReport` groups `session_health` by `(primaryProvider, hasGms, rom, sdkVersion)` over
a time range and returns medians of each metric plus session counts. Groups with < 3 sessions are
flagged `lowSample = true`.

| Method | Path | Result |
|---|---|---|
| GET | `/api/v1/admin/sessions/{id}/health` | `SessionHealthDto` |
| GET | `/api/v1/admin/reports/providers?from&to` | `ProviderReportDto` |
| GET | `/api/v1/admin/reports/providers.csv?from&to` | CSV, one row per group |

## Tasks

- [ ] T9.1 Protocol: `device` in `hello`, persistence of device info and status samples (V8).
- [ ] T9.2 Percentile helper, linear regression helper (pure, in `shared/stats`).
- [ ] T9.3 `SessionHealthCalculator` including gap and Doze heuristics.
- [ ] T9.4 `ComputeSessionHealth` on close + admin recompute; `session_health` repository.
- [ ] T9.5 `ProviderComparisonReport` + JSON/CSV endpoints.
- [ ] T9.6 Simulator: `--provider AOSP_GPS --doze-gap 6m` to generate comparable sessions.
- [ ] T9.7 Document how to run a field comparison (same route, two phones/stacks) in `docs/data/field-test.md`.

## Test plan

| ID | Type | Given / When / Then | Priority |
|---|---|---|---|
| TC-9-STAT-01 | Unit | Percentile of `[1..100]`: p50 = 50.5, p95 = 95.05 (linear interpolation, documented method) | P0 |
| TC-9-STAT-02 | Unit | Percentile of a single value = that value; empty list → null | P0 |
| TC-9-STAT-03 | Unit | Regression slope of perfectly linear 100→90 over 2 h = −5 %/h | P0 |
| TC-9-HLT-01 | Unit | Fixes every 5 s → `intervalP50S = 5`; no gaps | P0 |
| TC-9-HLT-02 | Unit | One 8-minute gap, not charging, motion STILL → one gap with `likelyDoze = true` | P0 |
| TC-9-HLT-03 | Unit | Same gap while charging → `likelyDoze = false` | P1 |
| TC-9-HLT-04 | Unit | Gap threshold uses `expectedIntervalS` from the last SetConstraint (60 s interval → a 90 s interval is not a gap) | P1 |
| TC-9-HLT-05 | Unit | Charging intervals are excluded from drain slope | P0 |
| TC-9-HLT-06 | Unit | `acceptedRatio` and `rejectionsByReason` match the stored batch rejections | P0 |
| TC-9-HLT-07 | Unit | Session with 0 fixes → metrics null/0, no exception | P0 |
| TC-9-HLT-08 | Unit | `providerMix` of 70 GMS + 30 AOSP_GPS fixes → 0.7 / 0.3 | P1 |
| TC-9-PRO-01 | Unit | Old-style `hello` without `device` still decodes (backward compat) | P0 |
| TC-9-DB-01 | Integration | Status samples and session health persist and round-trip | P0 |
| TC-9-RPT-01 | Integration | 3 GMS sessions + 2 AOSP sessions → two groups; AOSP group `lowSample = true` | P0 |
| TC-9-RPT-02 | Integration | `from/to` range excludes sessions outside it | P1 |
| TC-9-RPT-03 | API | CSV has a header row and one row per group; values use `.` decimal separator | P1 |
| TC-9-RPT-04 | API | Non-admin → 403 on all report endpoints | P0 |
| TC-9-E2E-01 | E2E | Simulator runs one GMS and one AOSP session with an injected Doze gap on AOSP → report shows higher gap count for AOSP | P1 |

## Test implementation notes

- **Stats helpers** have exact expected values; write them as literal numbers from a spreadsheet or
  hand calculation, and state the percentile method (linear interpolation between closest ranks) in KDoc.
- **Doze heuristic** tests build fixes and samples with the fixture builders (`aFix`, `aStatusSample`)
  at explicit timestamps — no GPX needed.

## Definition of Done

- After a field test with two phones, the providers report shows a meaningful comparison and exports to CSV.
- All P0 cases pass.
