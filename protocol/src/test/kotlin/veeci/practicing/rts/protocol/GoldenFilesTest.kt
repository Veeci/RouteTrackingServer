package veeci.practicing.rts.protocol

import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import com.networknt.schema.InputFormat
import com.networknt.schema.SchemaLocation
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.io.File
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * One golden file per message type: the frame exactly as it travels. Each file is checked three ways:
 * the code encodes to it, the code decodes it back, and the AsyncAPI document accepts it.
 * If one of the three changes alone, a test here fails, so code, samples and contract cannot drift apart.
 */
class GoldenFilesTest {
    @ParameterizedTest(name = "{0}")
    @MethodSource("samples")
    fun `TC-2-PRO-05 the message encodes to its golden file`(sample: Sample<*>) {
        sample.encode() shouldBe Json.parseToJsonElement(sample.golden())
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("samples")
    fun `TC-2-PRO-01 the golden file decodes back to the same message`(sample: Sample<*>) {
        sample.decode(sample.golden()) shouldBe sample.message
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("samples")
    fun `TC-2-CON-01 the golden file matches the AsyncAPI schema of its type`(sample: Sample<*>) {
        validate(sample.type, sample.golden()).shouldBeEmpty()
    }

    @Test
    fun `the schema check rejects frames that break the contract`() {
        validate("ack", """{"type":"ack","accepted":1,"rejected":[]}""").shouldNotBeEmpty() // no seq
        validate("ack", """{"type":"ack","seq":1,"accepted":1,"rejected":[],"extra":1}""").shouldNotBeEmpty()
        validate("error", """{"type":"error","code":"NOT_A_CODE","message":"m"}""").shouldNotBeEmpty()
    }

    @Test
    fun `every message type has a sample, a golden file and an AsyncAPI message`() {
        val inCode =
            listOf(ClientMessage.serializer(), ServerMessage.serializer(), GuestClientMessage.serializer(), GuestServerMessage.serializer())
                .flatMap { it.descriptor.subclassNames() }
                .toSet()
        val goldenFiles = File(javaClass.getResource("/golden")!!.toURI()).list()!!.map { it.removeSuffix(".json") }.toSet()

        samples().map { it.type }.toSet() shouldBe inCode
        goldenFiles shouldBe samples().map { it.file }.toSet()
        asyncApiMessageNames() shouldBe inCode
    }

    private fun validate(
        type: String,
        frame: String,
    ) = schemas
        .getSchema(SchemaLocation.of("$ASYNC_API#/components/messages/$type/payload"))
        .validate(frame, InputFormat.JSON)

    private fun asyncApiMessageNames(): Set<String> {
        val document = YAMLMapper().readTree(javaClass.getResource("/asyncapi/rts-ws-v1.yaml"))
        return document
            .path("components")
            .path("messages")
            .fieldNames()
            .asSequence()
            .toSet()
    }

    /** A sealed serializer's descriptor holds one element per subclass, named by its `@SerialName`. */
    @OptIn(ExperimentalSerializationApi::class)
    private fun SerialDescriptor.subclassNames(): Set<String> = getElementDescriptor(1).elementNames.toSet()

    /**
     * A message, the serializer of its sealed family (which writes `type`), and its golden file `golden/<file>.json`.
     * [file] differs from [type] when one type has several shapes worth pinning (a `trip_event` per event).
     */
    class Sample<T : Any>(
        val type: String,
        val message: T,
        private val serializer: KSerializer<T>,
        val file: String = type,
    ) {
        fun golden(): String = Sample::class.java.getResource("/golden/$file.json")!!.readText()

        fun encode() = ProtocolJson.encodeToJsonElement(serializer, message)

        fun decode(frame: String): T = ProtocolJson.decodeFromString(serializer, frame)

        override fun toString() = file
    }

    companion object {
        private const val ASYNC_API = "classpath:asyncapi/rts-ws-v1.yaml"
        private val schemas = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_7)

        private const val SESSION = "0f8fad5b-d9cb-469f-a165-70867728950e"
        private const val TRIP = "3b241101-e2bb-4255-8caf-4136c566a962"
        private const val CMD = "6ba7b810-9dad-11d1-80b4-00c04fd430c8"
        private val at = Instant.parse("2026-09-30T02:15:04.120Z")

        @JvmStatic
        fun samples(): List<Sample<*>> = driverSamples() + commandSamples() + guestSamples() + tripEventSamples()

        private fun driverSamples(): List<Sample<*>> =
            listOf(
                Sample("hello", Hello(1, "dev-sim-1", SESSION, lastAckedSeq = 41, sdkVersion = "0.9.0"), ClientMessage.serializer()),
                Sample(
                    "fix_batch",
                    FixBatch(
                        seq = 42,
                        fixes =
                            listOf(
                                FixDto(
                                    lat = 10.7769,
                                    lng = 106.7009,
                                    accuracyM = 8.5,
                                    speedMps = 11.2,
                                    speedAccuracyMps = 0.8,
                                    bearingDeg = 87.0,
                                    bearingAccuracyDeg = 4.0,
                                    altitudeM = 12.0,
                                    verticalAccuracyM = 3.5,
                                    recordedAt = at,
                                    provider = LocationProviderDto.GMS_FUSED,
                                    satellites = SatellitesDto(usedInFix = 14, meanCn0DbHz = 31.5),
                                ),
                                // Only the required fields: the defaults are still written out.
                                FixDto(lat = 10.7771, lng = 106.7012, accuracyM = 80.0, recordedAt = at + 1.seconds),
                            ),
                    ),
                    ClientMessage.serializer(),
                ),
                Sample(
                    "device_status",
                    DeviceStatus(
                        at = Instant.parse("2026-09-30T02:15:30Z"),
                        battery = BatteryDto(levelPercent = 14, isCharging = false, temperatureC = 38.5, profile = "LOW"),
                        motion = "IN_VEHICLE",
                    ),
                    ClientMessage.serializer(),
                ),
                Sample(
                    "welcome",
                    Welcome(
                        SESSION,
                        resumeFromSeq = 42,
                        serverTime = Instant.parse("2026-09-30T02:15:03.500Z"),
                        LimitsDto(100, 65_536, 20),
                    ),
                    ServerMessage.serializer(),
                ),
                Sample("ack", Ack(seq = 42, accepted = 1, rejected = listOf(RejectionDto(1, "POOR_ACCURACY"))), ServerMessage.serializer()),
                Sample(
                    "error",
                    ErrorMessage("BATCH_TOO_LARGE", "A batch holds at most 100 fixes; this one has 120", correlatesTo = 43),
                    ServerMessage.serializer(),
                ),
            )

        private fun guestSamples(): List<Sample<*>> =
            listOf(
                Sample("subscribe", Subscribe(TRIP), GuestClientMessage.serializer()),
                Sample(
                    "subscribed",
                    Subscribed(
                        GuestTrip(
                            tripId = TRIP,
                            status = "ACCEPTED",
                            pickup = Point(10.7725, 106.698),
                            destination = Point(10.7798, 106.699),
                            driverId = "7c9e6679-7425-40de-944b-e07fc1f90ae7",
                            updatedAt = Instant.parse("2026-09-30T02:15:00Z"),
                        ),
                    ),
                    GuestServerMessage.serializer(),
                ),
                Sample(
                    "subscribed",
                    Subscribed(
                        GuestTrip(
                            tripId = TRIP,
                            status = "IN_PROGRESS",
                            pickup = Point(10.7725, 106.698),
                            destination = Point(10.7798, 106.699),
                            driverId = "7c9e6679-7425-40de-944b-e07fc1f90ae7",
                            updatedAt = Instant.parse("2026-09-30T02:15:00Z"),
                            route = GuestRoute("TRIP", routeVersion = 1, "_wd`Ase}iSkk@_E", distanceM = 812.4, durationS = 97.5),
                        ),
                    ),
                    GuestServerMessage.serializer(),
                    file = "subscribed_route",
                ),
                Sample(
                    "driver_position",
                    DriverPosition(TRIP, 10.7769, 106.7009, bearingDeg = 87.0, speedMps = 11.2, recordedAt = at),
                    GuestServerMessage.serializer(),
                ),
                Sample(
                    "trip_progress",
                    TripProgress(TRIP, "PICKUP", remainingM = 1240.5, etaS = 112.0, basis = "STRAIGHT_LINE"),
                    GuestServerMessage.serializer(),
                ),
                Sample(
                    "trip_progress",
                    TripProgress(TRIP, "DESTINATION", remainingM = 640.0, etaS = 88.5, basis = "ROUTE", routeVersion = 1),
                    GuestServerMessage.serializer(),
                    file = "trip_progress_route",
                ),
            )

        /** Commands for the device and its acks (phase 5). */
        private fun commandSamples(): List<Sample<*>> =
            listOf(
                Sample("command_ack", CommandAck(CMD, CommandAck.APPLIED), ClientMessage.serializer()),
                Sample(
                    "command",
                    Command(CMD, "SET_CONSTRAINT", maxAccuracy = "HIGH", minIntervalMs = 1000, reason = "guest_watching"),
                    ServerMessage.serializer(),
                ),
                Sample(
                    "command",
                    Command(CMD, "REGISTER_GEOFENCES", regions = listOf(GeofenceDto("pickup", 10.7725, 106.698, 150.0))),
                    ServerMessage.serializer(),
                    file = "command_geofences",
                ),
            )

        /** One file per `trip_event` kind: the frame differs by its optional fields. */
        private fun tripEventSamples(): List<Sample<*>> =
            listOf(
                Sample(
                    "trip_event",
                    TripEvent(TRIP, TripEvent.STATUS_CHANGED, "ARRIVED", Instant.parse("2026-09-30T02:17:30Z")),
                    GuestServerMessage.serializer(),
                ),
                Sample(
                    "trip_event",
                    TripEvent(TRIP, TripEvent.DRIVER_ARRIVING, "ACCEPTED", Instant.parse("2026-09-30T02:16:58Z"), remainingM = 184.2),
                    GuestServerMessage.serializer(),
                    file = "trip_event_driver_arriving",
                ),
                Sample(
                    "trip_event",
                    TripEvent(TRIP, TripEvent.DRIVER_LOW_BATTERY, "IN_PROGRESS", Instant.parse("2026-09-30T02:30:00Z"), levelPercent = 14),
                    GuestServerMessage.serializer(),
                    file = "trip_event_driver_low_battery",
                ),
                Sample(
                    "trip_event",
                    TripEvent(
                        TRIP,
                        TripEvent.DRIVER_STALE,
                        "IN_PROGRESS",
                        Instant.parse("2026-09-30T02:31:05Z"),
                        lastSeenAt = Instant.parse("2026-09-30T02:30:02Z"),
                    ),
                    GuestServerMessage.serializer(),
                    file = "trip_event_driver_stale",
                ),
                Sample(
                    "trip_event",
                    TripEvent(TRIP, TripEvent.DRIVER_BACK, "IN_PROGRESS", Instant.parse("2026-09-30T02:32:10Z")),
                    GuestServerMessage.serializer(),
                    file = "trip_event_driver_back",
                ),
            )
    }
}
