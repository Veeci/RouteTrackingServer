package veeci.practicing.rts.trip.adapter.inbound.web.export

import kotlinx.serialization.json.Json
import veeci.practicing.rts.tracking.application.api.TrackPoint
import veeci.practicing.rts.trip.adapter.inbound.web.TimelineEntryDto
import veeci.practicing.rts.trip.application.TrackExport
import veeci.practicing.rts.trip.domain.TimelineEntry
import java.io.OutputStream
import java.io.Writer

/**
 * Writes one leg as a GeoJSON FeatureCollection (RFC 7946, phase 8d). The first feature is the leg as a
 * LineString; then one Point per timeline entry, with the same properties as in `/track`. Positions are
 * `[lng, lat]`, the RFC's order, which is the reverse of most map APIs.
 *
 * The file is written page by page and the track is never held in memory. A timeline entry has no stored
 * position, so it takes the last fix at or before its time, found in the same pass that writes the line. An entry
 * outside the leg's window (both ends included), or before its first fix, gets `"geometry": null`, which the RFC allows. So does the
 * line when the leg has fewer than two fixes, because a LineString needs at least two positions.
 */
object GeoJsonWriter {
    const val CONTENT_TYPE = "application/geo+json"

    private val json = Json { encodeDefaults = false }

    suspend fun write(
        export: TrackExport,
        out: OutputStream,
    ) {
        val writer = out.bufferedWriter(Charsets.UTF_8)
        writer.write("""{"type":"FeatureCollection","features":[""")
        writer.write("\n")
        writer.write(
            """{"type":"Feature","properties":{"tripId":"${export.tripId.value}","leg":"${export.leg.name.lowercase()}"},"geometry":""",
        )

        val places = TimelinePlaces(export.timeline)
        // A LineString needs two positions: hold the first fix until a second one shows the line exists.
        var first: TrackPoint? = null
        var lineOpen = false
        export.pages.collect { page ->
            page.forEach { fix ->
                places.passed(fix)
                when {
                    lineOpen -> {
                        writer.position(fix, ",\n")
                    }

                    first == null -> {
                        first = fix
                    }

                    else -> {
                        writer.write("""{"type":"LineString","coordinates":[""")
                        writer.position(checkNotNull(first), "\n")
                        writer.position(fix, ",\n")
                        lineOpen = true
                    }
                }
            }
            writer.flush() // one page at a time reaches the client
        }
        writer.write(if (lineOpen) "\n]}}" else "null}")

        val window = export.window
        export.timeline.forEachIndexed { i, entry ->
            // The end counts here, unlike for fixes: COMPLETED belongs at the last fix of the ride.
            val fix = places.of(i)?.takeIf { window != null && entry.at in window.from..window.to }
            writer.write(",\n")
            writer.write("""{"type":"Feature","properties":""")
            writer.write(json.encodeToString(TimelineEntryDto.serializer(), TimelineEntryDto.of(entry)))
            writer.write(""","geometry":""")
            if (fix == null) {
                writer.write("null}")
            } else {
                writer.write("""{"type":"Point","coordinates":""")
                writer.position(fix, "")
                writer.write("}}")
            }
        }
        writer.write("\n]}\n")
        writer.flush() // not close: the route owns the stream
    }

    private fun Writer.position(
        fix: TrackPoint,
        before: String,
    ) {
        write(before)
        write("[${coordinate(fix.lng)},${coordinate(fix.lat)}]")
    }

    /**
     * Gives each timeline entry the last fix at or before its time. The timeline and the fixes are both in time
     * order, so one pointer moves through the timeline as the fixes pass: O(fixes + entries), with no fix stored.
     */
    private class TimelinePlaces(
        private val timeline: List<TimelineEntry>,
    ) {
        private val places = arrayOfNulls<TrackPoint>(timeline.size)
        private var next = 0
        private var last: TrackPoint? = null

        fun passed(fix: TrackPoint) {
            // Entries strictly before this fix get the previous fix; an entry at the same time waits for this one.
            while (next < timeline.size && timeline[next].at < fix.recordedAt) places[next++] = last
            last = fix
        }

        fun of(index: Int): TrackPoint? = if (index < next) places[index] else last
    }
}
