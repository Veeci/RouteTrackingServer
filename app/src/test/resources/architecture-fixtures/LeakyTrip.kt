package veeci.practicing.rts.trip.domain

import io.ktor.server.routing.Route
import org.jetbrains.exposed.v1.core.Table
import veeci.practicing.rts.shared.geo.GeoPoint
import veeci.practicing.rts.trip.application.TripService

class LeakyTrip(
    val pickup: GeoPoint,
    val route: Route,
    val table: Table,
    val service: TripService,
)
