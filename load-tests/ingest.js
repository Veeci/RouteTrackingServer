// TC-2-LOAD-01: many drivers send fixes at the same time; the server must answer every batch quickly.
//
// Each virtual user (VU) is one driver: it opens one WebSocket session, says hello, and then sends a batch of
// 10 fixes every 5 s until the test ends. The script measures the time from sending a batch to receiving its ack.
// The drivers start one after another over the first 30 s, because the server allows 300 requests per minute
// from one IP address and every WebSocket upgrade counts as a request.
//
// Run it against the compose stack (see docs/plan/phase-02-ingest.md, "Load test"):
//   docker run --rm --network route-tracking_default -v "$PWD/load-tests:/scripts:ro" \
//     -e WS_URL=ws://app:8080/ws/v1/driver grafana/k6:2.3.0 run /scripts/ingest.js
//
// Settings (environment variables): WS_URL, DRIVERS (default 200), DURATION_S (default 600 = 10 minutes).

import exec from 'k6/execution';
import { Counter, Rate, Trend } from 'k6/metrics';
import { WebSocket } from 'k6/websockets';

const WS_URL = __ENV.WS_URL || 'ws://localhost:8080/ws/v1/driver';
const DRIVERS = Number(__ENV.DRIVERS || 200);
const DURATION_S = Number(__ENV.DURATION_S || 600);

const RAMP_UP_S = 30;
const BATCH_INTERVAL_MS = 5000;
const FIXES_PER_BATCH = 10;
const FIX_INTERVAL_MS = BATCH_INTERVAL_MS / FIXES_PER_BATCH;
const SPEED_MPS = 10; // 36 km/h, a car in city traffic
const METERS_PER_DEGREE = 111_320;
const LAST_ACK_WAIT_MS = 3000;

const ackLatency = new Trend('ack_latency', true);
const batchErrors = new Rate('batch_errors'); // a batch that got an error or no ack at all
const fixesRejected = new Counter('fixes_rejected');
const batchesAcked = new Counter('batches_acked');

export const options = {
  scenarios: {
    drivers: {
      executor: 'per-vu-iterations',
      vus: DRIVERS,
      iterations: 1,
      maxDuration: `${RAMP_UP_S + DURATION_S + 60}s`,
    },
  },
  thresholds: {
    ack_latency: ['p(95)<250'],
    batch_errors: ['rate<0.001'],
    // The generated fixes are plausible, so the server must accept all of them. A rejection means the
    // script is wrong, and then the load on the database is lower than intended.
    fixes_rejected: ['count==0'],
  },
};

export default function () {
  const driver = exec.vu.idInTest; // 1..DRIVERS
  const startDelayMs = (RAMP_UP_S * 1000 * (driver - 1)) / DRIVERS;
  const endAt = exec.scenario.startTime + (RAMP_UP_S + DURATION_S) * 1000;
  setTimeout(() => drive(driver, endAt), startDelayMs);
}

function drive(driver, endAt) {
  const sessionId = crypto.randomUUID();
  const vehicle = startVehicle();
  const sentAt = new Map(); // seq -> time the batch was sent, until its ack arrives
  let seq = 0;
  let timer;

  const ws = new WebSocket(WS_URL);

  ws.onopen = () => {
    ws.send(JSON.stringify({ type: 'hello', protocolVersion: 1, deviceId: `k6-driver-${driver}`, sessionId, sdkVersion: 'k6' }));
  };

  ws.onmessage = (event) => {
    const message = JSON.parse(event.data);
    if (message.type === 'welcome') {
      sendBatch();
      timer = setInterval(sendBatch, BATCH_INTERVAL_MS);
    } else if (message.type === 'ack') {
      ackLatency.add(Date.now() - sentAt.get(message.seq));
      sentAt.delete(message.seq);
      batchErrors.add(false);
      batchesAcked.add(1);
      fixesRejected.add(message.rejected.length);
    } else {
      console.error(`driver ${driver}: ${event.data}`);
      if (message.correlatesTo !== undefined) sentAt.delete(message.correlatesTo);
      batchErrors.add(true);
    }
  };

  ws.onerror = (event) => {
    console.error(`driver ${driver}: ${event.error}`);
  };

  function sendBatch() {
    if (Date.now() >= endAt) {
      clearInterval(timer);
      // Give the last ack time to arrive. A batch without an ack after that counts as an error.
      setTimeout(() => {
        sentAt.forEach(() => batchErrors.add(true));
        ws.close();
      }, LAST_ACK_WAIT_MS);
      return;
    }
    seq += 1;
    const fixes = [];
    for (let i = 0; i < FIXES_PER_BATCH; i++) fixes.push(vehicle.next());
    sentAt.set(seq, Date.now());
    ws.send(JSON.stringify({ type: 'fix_batch', seq, fixes }));
  }
}

// A car that drives straight on at SPEED_MPS from a random point in Ho Chi Minh City, in a random direction.
// Fix times run on their own clock, FIX_INTERVAL_MS apart, so that the speed between two fixes is always the same.
function startVehicle() {
  let lat = 10.75 + Math.random() * 0.05;
  let lng = 106.65 + Math.random() * 0.05;
  const bearingDeg = Math.random() * 360;
  const bearing = (bearingDeg * Math.PI) / 180;
  const step = (SPEED_MPS * FIX_INTERVAL_MS) / 1000;
  let time = Date.now() - BATCH_INTERVAL_MS;
  return {
    next() {
      lat += (step * Math.cos(bearing)) / METERS_PER_DEGREE;
      lng += (step * Math.sin(bearing)) / (METERS_PER_DEGREE * Math.cos((lat * Math.PI) / 180));
      time += FIX_INTERVAL_MS;
      return {
        lat,
        lng,
        accuracyM: 5.0,
        speedMps: SPEED_MPS,
        bearingDeg,
        recordedAt: new Date(time).toISOString(),
        provider: 'GMS_FUSED',
        mock: false,
      };
    },
  };
}
