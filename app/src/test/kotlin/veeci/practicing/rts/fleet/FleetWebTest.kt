package veeci.practicing.rts.fleet

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.ktor.http.parametersOf
import org.junit.jupiter.api.Test
import veeci.practicing.rts.fleet.adapter.inbound.web.DriverListQuery
import veeci.practicing.rts.fleet.adapter.inbound.web.FleetCsv
import veeci.practicing.rts.fleet.adapter.inbound.web.FleetCursors
import veeci.practicing.rts.fleet.application.FleetRow
import veeci.practicing.rts.fleet.domain.DeviceState
import veeci.practicing.rts.fleet.domain.DriverPosition
import veeci.practicing.rts.fleet.domain.FleetCursor
import veeci.practicing.rts.fleet.domain.FleetDriver
import veeci.practicing.rts.platform.http.RequestInvalidException
import java.util.UUID
import kotlin.time.Instant

/** The fleet's web adapter without a server: query parsing, cursors and CSV rows. */
class FleetWebTest {
    private val id = UUID.fromString("0f6d3c1e-0000-4000-8000-000000000001")
    private val at = Instant.parse("2026-11-17T08:00:00.5Z")

    @Test
    fun `every invalid list parameter is reported in one answer`() {
        val error =
            shouldThrow<RequestInvalidException> {
                DriverListQuery.parse(
                    parametersOf(
                        "online" to listOf("yes"),
                        "bbox" to listOf("106,21,105,22"),
                        "limit" to listOf("0"),
                        "cursor" to listOf("nope"),
                    ),
                )
            }
        error.errors.map { it.field } shouldContainExactlyInAnyOrder listOf("online", "bbox", "limit", "cursor")
    }

    @Test
    fun `a valid query keeps every filter`() {
        val query =
            DriverListQuery.parse(
                parametersOf(
                    "onTrip" to listOf("false"),
                    "q" to listOf(" lan "),
                    "bbox" to listOf("105.8,21,105.9,21.1"),
                ),
            )

        query.filter.onTrip shouldBe false
        query.filter.q shouldBe "lan"
        query.filter.bbox?.maxLat shouldBe 21.1
        query.limit shouldBe 50
    }

    @Test
    fun `a cursor survives the round trip, also for a driver never seen`() {
        listOf(FleetCursor(at, id), FleetCursor(null, id)).forEach { FleetCursors.decode(FleetCursors.encode(it)) shouldBe it }
    }

    @Test
    fun `TC-11-API-05 a CSV row uses dot decimals, quotes commas and guards formulas`() {
        val driver =
            FleetDriver(
                id,
                displayName = "=HYPERLINK(\"x\"), evil",
                position = DriverPosition(21.0000001, 105.8, 12.3, null, at),
                device = DeviceState(80, true, "IN_VEHICLE", at),
            )

        FleetCsv.header() shouldBe
            "driverId,displayName,enabled,online,lastSeenAt,lat,lng,speedMps,bearingDeg," +
            "batteryPct,isCharging,motion,currentTripId,tripStatus\r\n"
        FleetCsv.row(FleetRow(driver, online = true)) shouldBe
            "$id,\"'=HYPERLINK(\"\"x\"\"), evil\",true,true,2026-11-17T08:00:00.500Z," +
            "21.0000001,105.8,12.3,,80,true,IN_VEHICLE,,\r\n"
    }
}
