package veeci.practicing.rts.fleet.adapter.inbound.web

import kotlinx.serialization.Serializable
import veeci.practicing.rts.fleet.application.DriverDetail
import veeci.practicing.rts.fleet.application.FleetOverview
import veeci.practicing.rts.fleet.application.FleetRow
import kotlin.time.Instant

@Serializable
data class FleetPageResponse(
    val items: List<FleetDriverDto>,
    /** Pass it as `cursor` to get the next page; absent on the last page. */
    val nextCursor: String? = null,
    /** The time `online` was judged against. */
    val at: Instant,
)

@Serializable
data class FleetDriverDto(
    val driverId: String,
    val displayName: String? = null,
    val enabled: Boolean,
    /** Heard from within the stale time, judged on [heardAt] against the response's `at`. */
    val online: Boolean,
    /** When the newest position or status was measured, by the phone's clock. */
    val lastSeenAt: Instant? = null,
    /** When the server last heard from the driver, by the server's clock (fleet lag fix C). */
    val heardAt: Instant? = null,
    val position: PositionDto? = null,
    val device: DeviceDto? = null,
    val trip: TripStageDto? = null,
) {
    companion object {
        fun of(row: FleetRow): FleetDriverDto {
            val driver = row.driver
            return FleetDriverDto(
                driverId = driver.driverId.toString(),
                displayName = driver.displayName,
                enabled = driver.enabled,
                online = row.online,
                lastSeenAt = driver.lastSeenAt,
                heardAt = driver.heardAt,
                position = driver.position?.let { PositionDto(it.lat, it.lng, it.speedMps, it.bearingDeg, it.at) },
                device = driver.device?.let { DeviceDto(it.batteryPct, it.isCharging, it.motion, it.at) },
                trip = driver.trip?.let { TripStageDto(it.currentTripId?.toString(), it.status, it.at) },
            )
        }
    }
}

@Serializable
data class PositionDto(
    val lat: Double,
    val lng: Double,
    val speedMps: Double? = null,
    val bearingDeg: Double? = null,
    /** The fix's recorded time. */
    val at: Instant,
)

@Serializable
data class DeviceDto(
    val batteryPct: Int,
    val isCharging: Boolean,
    val motion: String? = null,
    val at: Instant,
)

@Serializable
data class TripStageDto(
    /** Absent once the trip has ended. */
    val currentTripId: String? = null,
    val status: String,
    val at: Instant,
)

@Serializable
data class DriverDetailResponse(
    val driver: FleetDriverDto,
    val disabledAt: Instant? = null,
    val disabledReason: String? = null,
    val recentSessions: List<SessionDto>,
    val recentTrips: List<DriverTripDto>,
    val health: HealthDto? = null,
    val at: Instant,
) {
    companion object {
        fun of(detail: DriverDetail) =
            DriverDetailResponse(
                driver = FleetDriverDto.of(detail.driver),
                disabledAt = detail.account?.disabledAt,
                disabledReason = detail.account?.disabledReason,
                recentSessions =
                    detail.sessions.map {
                        SessionDto(
                            it.id.toString(),
                            it.startedAt,
                            it.lastSeenAt,
                            it.device?.manufacturer,
                            it.device?.model,
                            it.device?.androidSdk,
                        )
                    },
                recentTrips =
                    detail.trips.map {
                        DriverTripDto(
                            it.id.toString(),
                            it.guestId.toString(),
                            it.status.name,
                            it.createdAt,
                            it.updatedAt,
                        )
                    },
                health =
                    detail.health?.let {
                        HealthDto(
                            it.sessionId.toString(),
                            it.startedAt,
                            it.computedAt,
                            it.fixCount,
                            it.acceptedRatio,
                            it.intervalP50S,
                            it.accuracyP50M,
                            it.gapCount,
                            it.batteryDrainPctPerHour,
                            it.primaryProvider,
                        )
                    },
                at = detail.at,
            )
    }
}

@Serializable
data class SessionDto(
    val sessionId: String,
    val startedAt: Instant,
    val lastSeenAt: Instant,
    val manufacturer: String? = null,
    val model: String? = null,
    val androidSdk: Int? = null,
)

@Serializable
data class DriverTripDto(
    val id: String,
    val guestId: String,
    val status: String,
    val createdAt: Instant,
    /** When the trip last changed stage. */
    val updatedAt: Instant,
)

@Serializable
data class HealthDto(
    val sessionId: String,
    val startedAt: Instant,
    val computedAt: Instant,
    val fixCount: Int,
    val acceptedRatio: Double? = null,
    val intervalP50S: Double? = null,
    val accuracyP50M: Double? = null,
    val gapCount: Int,
    val batteryDrainPctPerHour: Double? = null,
    val primaryProvider: String? = null,
)

@Serializable
data class FleetOverviewResponse(
    val onlineDrivers: Int,
    val driversOnTrip: Int,
    val activeTrips: Int,
    val disabledDrivers: Int,
    val computedAt: Instant,
) {
    companion object {
        fun of(overview: FleetOverview) =
            FleetOverviewResponse(
                overview.onlineDrivers,
                overview.driversOnTrip,
                overview.activeTrips,
                overview.disabledDrivers,
                overview.computedAt,
            )
    }
}

@Serializable
data class FleetRebuildResponse(
    /** Drivers in the rebuilt read model. */
    val drivers: Int,
)
