# Comparing location stacks: a field test

The provider report (phase 9) answers questions such as "does the AOSP location path on my ROM drop more fixes in
Doze than Google's fused provider?". It compares sessions grouped by primary provider, Google Play services, ROM
and Android SDK. This page says how to collect sessions worth comparing, on real phones or with the simulator, and
how to read the result.

## How a session gets into the report

1. The SDK sends `hello` with its `device` block, then fixes and `device_status` heartbeats.
2. 10 minutes after the session's last message, the health job computes its health. The job runs every 5 minutes,
   so a session appears 10 to 15 minutes after the phone stops sending.
3. The report reads the computed sessions that **started** in the range you ask for.

To see a session at once, compute it by hand (admin token):

```bash
curl -X POST -H "Authorization: Bearer $ADMIN" http://localhost:8080/api/v1/admin/sessions/$SESSION/health
```

## On real phones

The fairest comparison keeps everything the same except the location stack.

1. Use two phones on the same Android version if you can: one with Google Play services, one on an AOSP ROM such
   as LineageOS without GMS. Give each its own driver account.
2. Start tracking on both at the same moment, and carry them together: same car, same route, same stops.
3. Include what the comparison is about. For Doze, park for 10 minutes or more with the screen off and the phones
   still, not charging. A gap counts as likely Doze only when it lasts 5 minutes or more, and the status samples
   on both sides say STILL and not charging.
4. Stop tracking on both. Wait 15 minutes, or compute both sessions by hand.
5. Download the report for the day:

```bash
curl -H "Authorization: Bearer $ADMIN" -o providers.csv \
  "http://localhost:8080/api/v1/admin/reports/providers.csv?from=2026-11-17T00:00:00Z&to=2026-11-18T00:00:00Z"
```

Repeat the drive. A group with fewer than 3 sessions has `lowSample = true`: its medians can be one odd drive.

## With the simulator

The simulator fakes both phones on one laptop. Get a driver token for each, then run the two sessions one after
the other:

```bash
./gradlew :tools:simulator:run --args="token --role DRIVER"
```

```bash
./gradlew :tools:simulator:run --args="driver --token $GMS_DRIVER --rom stock --has-gms true"
```

```bash
./gradlew :tools:simulator:run --args="driver --token $AOSP_DRIVER --provider AOSP_GPS --rom LineageOS-22.1 --has-gms false --doze-gap 60:360"
```

`--doze-gap 60:360` makes the phone lie still for 6 minutes at 60 s of the route and send nothing. Every later fix
moves 6 minutes later, and the status samples near the gap say STILL. The other flags are in the README.

Compute both sessions by hand, or wait 15 minutes, then ask for the report. The AOSP group has one gap and one
likely-Doze gap, and the GMS group has none.

## Reading the report

| Column | Meaning |
|---|---|
| `sessions`, `lowSample` | How many sessions; true under 3 |
| `trackedHours` | Time from each session's start to its last message, summed |
| `acceptedRatio` | Accepted fixes over all fixes received |
| `intervalP50S`, `intervalP95S` | Time between accepted fixes |
| `accuracyP50M`, `accuracyP95M` | Reported accuracy of accepted fixes |
| `batteryDrainPctPerHour` | Battery percent lost per hour while not charging; positive means it runs down |
| `gapsPerHour`, `dozeGapsPerHour` | Gaps per tracked hour. A gap is longer than 3 times the expected interval and longer than 30 s |

Each value is the median over the group's sessions that have it. An empty cell means no session had a value, for
example no drain without a 10-minute stretch off the charger. A gap is measured against the interval the server
last told the phone to keep (SET_CONSTRAINT), so a phone told to send every 60 s is not blamed for 90 s pauses.

Decimals use a dot in every locale. A ROM name that starts with `=`, `+`, `-` or `@` gets a leading `'`, so a
spreadsheet shows it as text instead of running it as a formula.
