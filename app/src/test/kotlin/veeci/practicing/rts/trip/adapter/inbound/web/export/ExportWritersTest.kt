package veeci.practicing.rts.trip.adapter.inbound.web.export

import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import veeci.practicing.rts.shared.geo.GeoPoint
import veeci.practicing.rts.testing.geo.GpxTrack
import veeci.practicing.rts.testing.geo.offset
import veeci.practicing.rts.tracking.application.api.TrackCursor
import veeci.practicing.rts.tracking.application.api.TrackPoint
import veeci.practicing.rts.trip.application.TrackExport
import veeci.practicing.rts.trip.domain.TimeWindow
import veeci.practicing.rts.trip.domain.TimelineEntry
import veeci.practicing.rts.trip.domain.TripId
import veeci.practicing.rts.trip.domain.TripLeg
import veeci.practicing.rts.trip.domain.event.TripMoment
import veeci.practicing.rts.trip.domain.event.TripStatus
import java.io.ByteArrayOutputStream
import java.util.UUID
import javax.xml.XMLConstants
import javax.xml.transform.stream.StreamSource
import javax.xml.validation.SchemaFactory
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * GpxWriter and GeoJsonWriter on a fixed five-fix ride in two pages (phase 8d). The golden files in
 * src/test/resources/export pin the exact output; to change them on purpose, run with UPDATE_GOLDEN=true.
 */
class ExportWritersTest {
    private val tripId = TripId(UUID.fromString("6f1c2c1e-0f6a-4d43-9a3e-5b2f8c9d7e10"))
    private val t0 = Instant.parse("2026-11-16T08:00:00Z")
    private val start = GeoPoint(21.0285, 105.8542) // Hoan Kiem lake, Hanoi
    private val session = UUID.fromString("00000000-0000-0000-0000-000000000001")
    private val fixes = (0 until 5).map { fix(it, start.offset(northM = it * 50.0, eastM = it * 20.0)) }
    private val offRoute = TripMoment.OffRoute(tripId.value, UUID.randomUUID(), TripStatus.IN_PROGRESS, t0 + 25.seconds)
    private val timeline =
        listOf(
            TimelineEntry.StageReached(t0 - 300.seconds, TripStatus.REQUESTED), // before the ride: no position
            TimelineEntry.StageReached(t0, TripStatus.IN_PROGRESS), // at the first fix: that fix
            TimelineEntry.MomentRaised(offRoute), // between fix 2 and 3: fix 2
            TimelineEntry.StageReached(t0 + 50.seconds, TripStatus.COMPLETED), // at the window's end: the last fix
        )

    private fun fix(
        i: Int,
        at: GeoPoint,
    ) = TrackPoint(at.lat, at.lng, t0 + (i * 10).seconds, 5.0, 10.0, null, TrackCursor(t0 + (i * 10).seconds, session, 1, i))

    private fun export(pages: Flow<List<TrackPoint>> = flowOf(fixes.take(2), fixes.drop(2))) =
        TrackExport(tripId, TripLeg.RIDE, TimeWindow(t0, t0 + 50.seconds), timeline, pages)

    private suspend fun gpx(export: TrackExport = export()) =
        ByteArrayOutputStream()
            .also {
                GpxWriter.write(export, it)
            }.toString(Charsets.UTF_8)

    private suspend fun geoJson(export: TrackExport = export()) =
        ByteArrayOutputStream().also { GeoJsonWriter.write(export, it) }.toString(Charsets.UTF_8)

    @Test
    fun `TC-8-EXP-01 the GPX validates against the GPX 1_1 schema`() =
        runTest {
            val schema = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI).newSchema(javaClass.getResource("/gpx/gpx.xsd"))

            schema.newValidator().validate(StreamSource(gpx().reader())) // throws SAXException when invalid
            schema.newValidator().validate(StreamSource(gpx(export(emptyFlow())).reader()))
        }

    @Test
    fun `TC-8-EXP-05 GPX read back gives the same points within 1e-7 degrees, and the same times`() =
        runTest {
            val read = GpxTrack.points(gpx())

            read.size shouldBe fixes.size
            read.zip(fixes).forEach { (back, fix) ->
                back.lat shouldBe (fix.lat plusOrMinus 1e-7)
                back.lng shouldBe (fix.lng plusOrMinus 1e-7)
                back.time shouldBe fix.recordedAt
            }
        }

    @Test
    fun `TC-8-EXP-02 GeoJSON positions are lng then lat`() =
        runTest {
            val line = features(geoJson()).first().jsonObject["geometry"]!!.jsonObject

            line["type"]!!.jsonPrimitive.content shouldBe "LineString"
            val first = line["coordinates"]!!.jsonArray.first().jsonArray
            first[0].jsonPrimitive.double shouldBe (fixes.first().lng plusOrMinus 1e-7)
            first[1].jsonPrimitive.double shouldBe (fixes.first().lat plusOrMinus 1e-7)
        }

    @Test
    fun `each timeline point is at the last fix at or before it, and null before the ride`() =
        runTest {
            val points = features(geoJson()).drop(1).map { it.jsonObject["geometry"]!! }

            points[0] shouldBe JsonNull
            points[1].position() shouldBe fixes[0].lngLat()
            points[2].position() shouldBe fixes[2].lngLat()
            points[3].position() shouldBe fixes[4].lngLat()
        }

    @Test
    fun `a leg with fewer than two fixes has a null line, and the file is still valid JSON`() =
        runTest {
            listOf(emptyFlow(), flowOf(fixes.take(1))).forEach { pages ->
                val features = features(geoJson(export(pages)))
                features.first().jsonObject["geometry"] shouldBe JsonNull
                features.size shouldBe 1 + timeline.size
            }
        }

    @Test
    fun `both files match their golden files`() =
        runTest {
            golden("export/trip-ride.gpx", gpx())
            golden("export/trip-ride.geojson", geoJson())
        }

    private fun features(geoJson: String): JsonArray = Json.parseToJsonElement(geoJson).jsonObject["features"]!!.jsonArray

    private fun kotlinx.serialization.json.JsonElement.position() =
        (this as JsonObject)["coordinates"]!!.jsonArray.map {
            it.jsonPrimitive.double
        }

    private fun TrackPoint.lngLat() = listOf(coordinate(lng).toDouble(), coordinate(lat).toDouble())

    private fun golden(
        path: String,
        actual: String,
    ) {
        if (System.getenv("UPDATE_GOLDEN") == "true") {
            java.io
                .File("src/test/resources/$path")
                .apply { parentFile.mkdirs() }
                .writeText(actual)
        }
        val expected = checkNotNull(javaClass.getResource("/$path")) { "No golden file $path; run with UPDATE_GOLDEN=true" }.readText()
        actual shouldBe expected
    }
}
