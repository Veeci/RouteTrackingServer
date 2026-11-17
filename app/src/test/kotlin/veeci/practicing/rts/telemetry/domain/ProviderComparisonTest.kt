package veeci.practicing.rts.telemetry.domain

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class ProviderComparisonTest {
    private val t0 = Instant.parse("2026-11-17T08:00:00Z")
    private val gms = DeviceKey("Google", "Pixel 7", 35, null, hasGms = true)
    private val aosp = DeviceKey("Google", "Pixel 7", 35, "LineageOS 22.1", hasGms = false)

    private fun record(
        provider: String?,
        device: DeviceKey?,
        length: Duration = 1.hours,
        gaps: Int = 0,
        dozeGaps: Int = 0,
    ) = SessionHealthRecord(
        UUID.randomUUID(),
        null,
        t0,
        t0 + length,
        device,
        t0 + length,
        SessionHealth(
            fixCount = 10,
            rejectedCount = 0,
            acceptedRatio = 1.0,
            rejectionsByReason = emptyMap(),
            intervalP50S = 5.0,
            intervalP95S = null,
            accuracyP50M = null,
            accuracyP95M = null,
            gaps = List(gaps) { Gap(t0, 60.0, 5.0, likelyDoze = it < dozeGaps) },
            batteryDrainPctPerHour = null,
            providerMix = emptyMap(),
            primaryProvider = provider,
        ),
    )

    private fun SessionHealthRecord.interval(p50: Double?) = copy(health = health.copy(intervalP50S = p50))

    @Test
    fun `3 GMS and 2 AOSP sessions make two groups, and the AOSP one is a low sample`() {
        val records = List(3) { record("GMS_FUSED", gms) } + List(2) { record("AOSP_GPS", aosp, gaps = 2, dozeGaps = 1) }

        val groups = ProviderComparison.of(records)

        groups.map { it.key.primaryProvider to it.sessions } shouldBe listOf("AOSP_GPS" to 2, "GMS_FUSED" to 3)
        groups.map { it.lowSample } shouldBe listOf(true, false)
        groups[0].key shouldBe ProviderGroupKey("AOSP_GPS", hasGms = false, rom = "LineageOS 22.1", androidSdk = 35)
        groups[0].gapsPerHour shouldBe 2.0
        groups[0].dozeGapsPerHour shouldBe 1.0
        groups[1].gapsPerHour shouldBe 0.0
    }

    @Test
    fun `metrics are medians, and a session without a value is left out of that median`() {
        val records =
            listOf(
                record("GMS_FUSED", gms).interval(4.0),
                record("GMS_FUSED", gms).interval(6.0),
                record("GMS_FUSED", gms).interval(100.0), // one odd session moves a mean, not a median
                record("GMS_FUSED", gms).interval(null),
            )

        ProviderComparison.of(records).single().intervalP50S shouldBe 6.0
    }

    @Test
    fun `gaps are counted per hour, so a long session does not look worse for being long`() {
        val records = listOf(record("GMS_FUSED", gms, length = 3.hours, gaps = 3), record("GMS_FUSED", gms, length = 30.minutes, gaps = 1))

        val group = ProviderComparison.of(records).single()

        group.gapsPerHour shouldBe 1.5 // median of 1 and 2
        group.trackedHours shouldBe 3.5
    }

    @Test
    fun `sessions without a device or a provider form their own group, listed last`() {
        val groups = ProviderComparison.of(listOf(record(null, null, length = Duration.ZERO), record("GMS_FUSED", gms)))

        groups.last().key shouldBe ProviderGroupKey(null, null, null, null)
        groups.last().gapsPerHour.shouldBeNull() // no tracked time: no rate
    }
}
