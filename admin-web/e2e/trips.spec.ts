import { type APIRequestContext, expect, test } from '@playwright/test';
import { adminGet, logIn, type Minted, mint, serveBlankTiles, watchConsole } from './helpers';

// 12e against a real server. Booking a trip plans a road route, so the server must load a road graph. CI has no map
// of Hanoi, so the job starts the server with the test fixture `saigon_test_grid.osm` (ROUTING_GRAPHPATH), and the
// trip below lies inside that grid: the pickup and destination of the server's TripSamples.

const PICKUP = { lat: 10.7725, lng: 106.698 };
const DESTINATION = { lat: 10.7798, lng: 106.699 };

interface TripBody {
  id: string;
  status: string;
}

interface AuditBody {
  items: { action: string; targetId: string; reason?: string }[];
}

async function bookAndAccept(request: APIRequestContext, guest: Minted, driver: Minted): Promise<string> {
  const booked = await request.post('/api/v1/trips', {
    headers: { Authorization: `Bearer ${guest.token}`, 'Idempotency-Key': crypto.randomUUID() },
    data: { pickup: PICKUP, destination: DESTINATION },
  });
  expect(booked.ok(), `booking answered ${String(booked.status())}: ${await booked.text()}`).toBe(true);
  const trip = (await booked.json()) as TripBody;
  const accepted = await request.post(`/api/v1/trips/${trip.id}/accept`, {
    headers: { Authorization: `Bearer ${driver.token}` },
  });
  expect(accepted.ok(), `accept answered ${String(accepted.status())}`).toBe(true);
  return trip.id;
}

test('TC-12-E2E-04: an operator cancels a trip; the trip shows CANCELLED and the live view ends', async ({
  page,
  request,
}) => {
  const problems = watchConsole(page);
  await serveBlankTiles(page);
  const stamp = String(Date.now());
  const guest = await mint(request, 'GUEST', `E2E guest ${stamp}`);
  const driver = await mint(request, 'DRIVER', `E2E driver ${stamp}`);
  const tripId = await bookAndAccept(request, guest, driver);
  const reason = `E2E: guest asked by phone ${stamp}`;

  // The list finds the trip by its guest. A deep link without a session goes through the login and comes back.
  await page.goto(`/trips?guestId=${guest.userId}`);
  await logIn(page, 'E2E operator', 'Operator');
  const table = page.getByRole('table', { name: 'Trips' });
  await expect(table.getByText('Accepted')).toBeVisible();
  await table.getByRole('link', { name: tripId.slice(0, 8) }).click();

  // Live mode: the staff trip socket is open.
  await expect(page.getByRole('heading', { name: 'Live', exact: true })).toBeVisible();
  const liveStatus = page.getByRole('region', { name: 'Live status' });
  await expect(liveStatus.getByText('Live', { exact: true })).toBeVisible();

  await page.getByRole('button', { name: 'Cancel trip' }).click();
  const dialog = page.getByRole('dialog', { name: 'Cancel this trip' });
  await dialog.getByLabel(/Reason/).fill(reason);
  await dialog.getByRole('button', { name: 'Cancel trip' }).click();
  await expect(dialog).toBeHidden();

  // The trip shows CANCELLED, the live view is gone, and the replay of the ended trip took its place.
  await expect(page.getByRole('heading', { name: 'Replay' })).toBeVisible();
  await expect(page.getByText('Cancelled', { exact: true }).first()).toBeVisible();
  await expect(liveStatus).toBeHidden();
  await expect(page.getByRole('button', { name: 'Cancel trip' })).toBeHidden();
  await expect(page.getByRole('table', { name: 'Timeline' }).getByText('Stage: Cancelled')).toBeVisible();

  // The server agrees, and the audit log keeps the reason the guest and the driver never see.
  const admin = await mint(request, 'ADMIN', 'E2E check');
  const trip = await adminGet<TripBody>(request, admin.token, `/api/v1/trips/${tripId}`);
  expect(trip.status).toBe('CANCELLED');
  const audit = await adminGet<AuditBody>(
    request,
    admin.token,
    `/api/v1/admin/audit?action=TRIP_CANCELLED&targetId=${tripId}`,
  );
  expect(audit.items.map((item) => item.reason)).toEqual([reason]);
  expect(problems).toEqual([]);
});
