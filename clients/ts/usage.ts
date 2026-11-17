// Compiled by CI (job ts-client, TC-11-CI-01) next to the generated types. It uses the types the way the admin
// dashboard does, so a change to rts-v1.yaml that breaks those uses fails CI here, before the dashboard pins it.
import type { components, paths } from "./generated/rts-v1";

type Trip = components["schemas"]["Trip"];
type TripPage = components["schemas"]["TripPage"];
type TripStatus = components["schemas"]["TripStatus"];
type FleetPage = components["schemas"]["FleetPage"];
type WsTicket = components["schemas"]["WsTicket"];

type TripListQuery = NonNullable<paths["/api/v1/admin/trips"]["get"]["parameters"]["query"]>;
type CancelBody = paths["/api/v1/admin/trips/{id}/cancel"]["post"]["requestBody"]["content"]["application/json"];
type CancelOk = paths["/api/v1/admin/trips/{id}/cancel"]["post"]["responses"]["200"]["content"]["application/json"];
type DriverListOk = paths["/api/v1/admin/drivers"]["get"]["responses"]["200"]["content"]["application/json"];

// The status filter repeats in the query string: an array of the trip stages.
const activeTrips: TripListQuery = { status: ["ACCEPTED", "ARRIVED", "IN_PROGRESS"], limit: 50 };

// The reason is required: the dashboard cannot send a cancel without one.
const cancel: CancelBody = { reason: "The guest called support" };

function isEnded(status: TripStatus): boolean {
  return status === "COMPLETED" || status === "CANCELLED";
}

function nextPage(page: TripPage): string | undefined {
  return page.nextCursor;
}

function label(trip: CancelOk & Trip): string {
  return `${trip.id} ${trip.status}${isEnded(trip.status) ? " (ended)" : ""}`;
}

function onlineCount(page: DriverListOk & FleetPage): number {
  return page.items.filter((driver) => driver.online).length;
}

function socketUrl(base: string, ticket: WsTicket): string {
  return `${base}/ws/v1/admin/fleet?ticket=${encodeURIComponent(ticket.ticket)}`;
}

export const examples = { activeTrips, cancel, nextPage, label, onlineCount, socketUrl };
