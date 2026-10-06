package veeci.practicing.rts.tracking.domain

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldBeSorted
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.double
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import veeci.practicing.rts.shared.geo.GeoPoint
import veeci.practicing.rts.tracking.domain.Readings.NOW
import veeci.practicing.rts.tracking.domain.Readings.START
import veeci.practicing.rts.tracking.domain.Readings.northOf
import veeci.practicing.rts.tracking.domain.Readings.previouslyAccepted
import veeci.practicing.rts.tracking.domain.Readings.reading
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class FixPipelineTest {
    private val pipeline = FixPipeline(PipelineConfig())

    private fun process(
        vararg batch: FixReading,
        previous: AcceptedFix? = null,
        pipeline: FixPipeline = this.pipeline,
    ) = pipeline.process(batch.toList(), PipelineContext(NOW, previous))

    /** index → reason of every rejected fix. */
    private fun PipelineResult.reasons() = rejected.associate { it.index to it.reason }

    @Nested
    inner class SingleFixRules {
        @Test
        fun `TC-2-PIP-01 an accuracy radius above 50 m is POOR_ACCURACY`() {
            process(reading(accuracyM = 80.0)).reasons() shouldBe mapOf(0 to RejectionReason.POOR_ACCURACY)
        }

        @Test
        fun `a very large accuracy radius is poor, not invalid`() {
            process(reading(accuracyM = 1_500.0)).reasons() shouldBe mapOf(0 to RejectionReason.POOR_ACCURACY)
        }

        @Test
        fun `TC-2-PIP-02 a fix 2 minutes in the future is FUTURE_TIMESTAMP, 10 seconds is within the clock skew`() {
            process(reading(at = NOW + 2.minutes)).reasons() shouldBe mapOf(0 to RejectionReason.FUTURE_TIMESTAMP)
            process(reading(at = NOW + 10.seconds)).rejected.shouldBeEmpty()
        }

        @Test
        fun `TC-2-PIP-03 a fix older than 24 hours is TOO_OLD`() {
            process(reading(at = NOW - 25.hours)).reasons() shouldBe mapOf(0 to RejectionReason.TOO_OLD)
        }

        @Test
        fun `TC-2-PIP-04 every reported value is checked, not only the position`() {
            val broken =
                mapOf(
                    "lat NaN" to reading().copy(lat = Double.NaN),
                    "speedMps -1" to reading().copy(speedMps = -1.0),
                    "speedAccuracyMps NaN" to reading().copy(speedAccuracyMps = Double.NaN),
                    "bearingDeg 360" to reading().copy(bearingDeg = 360.0),
                    "bearingDeg -1" to reading().copy(bearingDeg = -1.0),
                    "bearingAccuracyDeg -1" to reading().copy(bearingAccuracyDeg = -1.0),
                    "altitudeM infinite" to reading().copy(altitudeM = Double.POSITIVE_INFINITY),
                    "verticalAccuracyM -1" to reading().copy(verticalAccuracyM = -1.0),
                    "satellites.usedInFix -1" to reading(satellites = SatelliteHealth(-1, 30.0)),
                    "satellites.meanCn0DbHz NaN" to reading(satellites = SatelliteHealth(10, Double.NaN)),
                )

            broken.forEach { (case, reading) ->
                withClue(case) { process(reading).reasons() shouldBe mapOf(0 to RejectionReason.INVALID_VALUE) }
            }
        }

        @Test
        fun `edge values are valid - bearing 0 is north, altitude may be below sea level, 0 satellites is a count`() {
            val result =
                process(
                    reading().copy(bearingDeg = 0.0, altitudeM = -28.0),
                    reading(at = NOW + 1.seconds, satellites = SatelliteHealth(0, null)),
                )

            result.rejected.shouldBeEmpty()
        }

        @Test
        fun `TC-2-PIP-17 a mock fix is MOCK_LOCATION unless mock fixes are allowed`() {
            process(reading(mock = true)).reasons() shouldBe mapOf(0 to RejectionReason.MOCK_LOCATION)

            val allowMock = FixPipeline(PipelineConfig(rejectMock = false))
            process(reading(mock = true), pipeline = allowMock).rejected.shouldBeEmpty()
        }

        @Test
        fun `the first rule that objects gives the reason`() {
            process(reading(accuracyM = 80.0, mock = true)).reasons() shouldBe mapOf(0 to RejectionReason.MOCK_LOCATION)
        }
    }

    @Nested
    inner class Duplicates {
        @Test
        fun `TC-2-PIP-05 the second of two fixes with the same time is DUPLICATE_TIMESTAMP`() {
            process(reading(), reading(point = northOf(START, 5.0))).reasons() shouldBe
                mapOf(1 to RejectionReason.DUPLICATE_TIMESTAMP)
        }

        @Test
        fun `TC-2-PIP-06 a fix with the same time as the previous batch's accepted fix is DUPLICATE_TIMESTAMP`() {
            val previous = previouslyAccepted(reading())

            process(reading(), previous = previous).reasons() shouldBe mapOf(0 to RejectionReason.DUPLICATE_TIMESTAMP)
        }
    }

    @Nested
    inner class Jumps {
        @Test
        fun `TC-2-PIP-07 2 km in 10 seconds (200 m per s) is IMPLAUSIBLE_JUMP`() {
            val result = process(reading(at = NOW - 10.seconds), reading(at = NOW, point = northOf(START, 2_000.0)))

            result.reasons() shouldBe mapOf(1 to RejectionReason.IMPLAUSIBLE_JUMP)
        }

        @Test
        fun `TC-2-PIP-08 the accuracy radii are taken off the distance before the speed check`() {
            // 200 m in 2 s is 100 m/s, but (200 - 40 - 40) m / 2 s = 60 m/s is under the 70 m/s limit.
            val result =
                process(
                    reading(at = NOW - 2.seconds, accuracyM = 40.0),
                    reading(at = NOW, point = northOf(START, 200.0), accuracyM = 40.0),
                )

            result.rejected.shouldBeEmpty()
        }

        @Test
        fun `TC-2-PIP-09 after a rejected jump the next fix is compared with the last accepted fix`() {
            val result =
                process(
                    reading(at = NOW - 20.seconds),
                    reading(at = NOW - 10.seconds, point = northOf(START, 2_000.0)),
                    reading(at = NOW, point = northOf(START, 100.0)),
                )

            result.reasons() shouldBe mapOf(1 to RejectionReason.IMPLAUSIBLE_JUMP)
            result.accepted
                .last()
                .distanceFromPrev.value shouldBe (100.0 plusOrMinus 0.01)
        }

        @Test
        fun `TC-2-PIP-10 a fix from fewer than 4 satellites gets half the accuracy tolerance`() {
            fun jumpWith(satellites: Int) =
                process(
                    reading(at = NOW - 2.seconds, accuracyM = 40.0),
                    reading(at = NOW, point = northOf(START, 200.0), accuracyM = 40.0, satellites = SatelliteHealth(satellites, 30.0)),
                )

            // Half tolerance: (200 - 40) m / 2 s = 80 m/s, over the limit. Full tolerance: 60 m/s.
            jumpWith(satellites = 3).reasons() shouldBe mapOf(1 to RejectionReason.IMPLAUSIBLE_JUMP)
            jumpWith(satellites = 12).rejected.shouldBeEmpty()
        }

        @Test
        fun `TC-2-PIP-13 the first fix of a session is never a jump and has distance 0`() {
            val far = GeoPoint(21.0285, 105.8542) // Hanoi, 1,100 km from START

            val first = process(reading(point = far)).accepted.single()

            first.distanceFromPrev.value shouldBe 0.0
            first.derivedSpeedMps.shouldBeNull()
        }
    }

    @Nested
    inner class OrderAndEnrichment {
        @Test
        fun `TC-2-PIP-11 a batch out of time order is processed in time order, with the original indexes`() {
            val result =
                process(
                    reading(at = NOW + 20.seconds - 1.minutes, point = northOf(START, 200.0)),
                    reading(at = NOW - 1.minutes),
                    reading(at = NOW + 10.seconds - 1.minutes, accuracyM = 80.0),
                )

            result.accepted.map { it.index } shouldBe listOf(1, 0)
            result.rejected shouldBe listOf(Rejection(2, RejectionReason.POOR_ACCURACY))
            result.accepted
                .last()
                .distanceFromPrev.value shouldBe (200.0 plusOrMinus 0.01)
        }

        @Test
        fun `TC-2-PIP-12 distances and the running total of three fixes 100 m apart`() {
            val batch =
                arrayOf(
                    reading(at = NOW - 20.seconds),
                    reading(at = NOW - 10.seconds, point = northOf(START, 100.0)),
                    reading(at = NOW, point = northOf(START, 200.0)),
                )

            val alone = process(*batch).accepted
            alone.map { it.distanceFromPrev.value }.zip(listOf(0.0, 100.0, 100.0)).forEach { (actual, expected) ->
                actual shouldBe (expected plusOrMinus 0.01)
            }
            alone.map { it.cumulative.value }.zip(listOf(0.0, 100.0, 200.0)).forEach { (actual, expected) ->
                actual shouldBe (expected plusOrMinus 0.01)
            }
            alone[1].derivedSpeedMps!! shouldBe (10.0 plusOrMinus 0.001)

            // With a fix from an earlier batch 100 m south, the first fix continues that session's total.
            val previous = previouslyAccepted(reading(at = NOW - 30.seconds, point = northOf(START, -100.0)), cumulativeM = 500.0)
            val continued = process(*batch, previous = previous).accepted.first()
            continued.distanceFromPrev.value shouldBe (100.0 plusOrMinus 0.01)
            continued.cumulative.value shouldBe (600.0 plusOrMinus 0.01)
        }
    }

    @Nested
    inner class Properties {
        /** Readings that cover every path: valid, invalid (negative or NaN accuracy), poor, mock, future, too old. */
        private val anyReading =
            arbitrary {
                reading(
                    at = NOW + Arb.long(-90_000L..120L).bind().seconds,
                    point =
                        GeoPoint(
                            Arb.double(10.76..10.78, includeNaNs = false).bind(),
                            Arb.double(106.69..106.71, includeNaNs = false).bind(),
                        ),
                    accuracyM = Arb.double(-1.0..100.0).bind(),
                    mock = Arb.boolean().bind(),
                )
            }

        @Test
        fun `TC-2-PIP-14 every fix of a batch is either accepted or rejected, exactly once`() =
            runTest {
                checkAll(Arb.list(anyReading, 0..40)) { batch ->
                    val result = pipeline.process(batch, PipelineContext(NOW, previous = null))

                    (result.accepted.map { it.index } + result.rejected.map { it.index }).sorted() shouldBe batch.indices.toList()
                }
            }

        @Test
        fun `TC-2-PIP-15 accepted fixes come in time order and the running total never goes down`() =
            runTest {
                checkAll(Arb.list(anyReading, 0..40)) { batch ->
                    val accepted = pipeline.process(batch, PipelineContext(NOW, previous = null)).accepted

                    accepted.map { it.fix.recordedAt }.shouldBeSorted()
                    accepted.map { it.cumulative.value }.shouldBeSorted()
                }
            }
    }
}
