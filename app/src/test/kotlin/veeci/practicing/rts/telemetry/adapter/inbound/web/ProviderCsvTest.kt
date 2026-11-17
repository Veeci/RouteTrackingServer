package veeci.practicing.rts.telemetry.adapter.inbound.web

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import veeci.practicing.rts.telemetry.domain.ProviderGroup
import veeci.practicing.rts.telemetry.domain.ProviderGroupKey
import java.util.Locale

class ProviderCsvTest {
    private fun group(
        rom: String?,
        drain: Double? = 6.25,
    ) = ProviderGroup(
        ProviderGroupKey("AOSP_GPS", false, rom, 35),
        sessions = 2,
        lowSample = true,
        trackedHours = 3.5,
        acceptedRatio = 0.0001,
        intervalP50S = 5.0,
        intervalP95S = null,
        accuracyP50M = null,
        accuracyP95M = null,
        batteryDrainPctPerHour = drain,
        gapsPerHour = 2.0,
        dozeGapsPerHour = 0.0,
    )

    private fun rows(csv: String) = csv.split("\r\n").dropLast(1)

    @Test
    fun `TC-9-RPT-03 a header row, one row per group, and decimals with a dot in any locale`() {
        val previous = Locale.getDefault()
        Locale.setDefault(Locale.GERMANY) // writes 6,25 with String.format and the default locale
        try {
            val rows = rows(ProviderCsv.write(listOf(group("LineageOS 22.1"), group(null, drain = null))))

            rows.size shouldBe 3
            rows[0].split(",").take(4) shouldBe listOf("primaryProvider", "hasGms", "rom", "androidSdk")
            rows[1] shouldBe "AOSP_GPS,false,LineageOS 22.1,35,2,true,3.5,0.0001,5,,,,6.25,2,0"
            rows[2] shouldBe "AOSP_GPS,false,,35,2,true,3.5,0.0001,5,,,,,2,0"
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun `a ROM name with a comma or a quote is quoted, and one that starts like a formula is made text`() {
        val rows = rows(ProviderCsv.write(listOf(group("Lineage, \"beta\""), group("=HYPERLINK(\"x\")"))))

        rows[1].split(",").take(3).joinToString(",") shouldBe "AOSP_GPS,false,\"Lineage"
        rows[1] shouldBe "AOSP_GPS,false,\"Lineage, \"\"beta\"\"\",35,2,true,3.5,0.0001,5,,,,6.25,2,0"
        rows[2] shouldBe "AOSP_GPS,false,\"'=HYPERLINK(\"\"x\"\")\",35,2,true,3.5,0.0001,5,,,,6.25,2,0"
    }
}
