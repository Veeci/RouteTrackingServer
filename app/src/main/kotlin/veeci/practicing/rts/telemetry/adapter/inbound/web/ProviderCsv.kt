package veeci.practicing.rts.telemetry.adapter.inbound.web

import veeci.practicing.rts.telemetry.domain.ProviderGroup
import java.math.BigDecimal

/**
 * The provider report as CSV (RFC 4180): a header row, then one row per group. Numbers are plain decimals with a
 * `.`, whatever the server's locale. A null is an empty cell. A text cell that a spreadsheet would run as a formula
 * (it starts with `=`, `+`, `-` or `@`) gets a leading `'`: the ROM name comes from the phone, so it is untrusted.
 */
object ProviderCsv {
    const val CONTENT_TYPE = "text/csv"

    private val HEADER =
        listOf(
            "primaryProvider",
            "hasGms",
            "rom",
            "androidSdk",
            "sessions",
            "lowSample",
            "trackedHours",
            "acceptedRatio",
            "intervalP50S",
            "intervalP95S",
            "accuracyP50M",
            "accuracyP95M",
            "batteryDrainPctPerHour",
            "gapsPerHour",
            "dozeGapsPerHour",
        )

    private val FORMULA_START = setOf('=', '+', '-', '@')

    fun write(groups: List<ProviderGroup>): String =
        buildString {
            append(HEADER.joinToString(",")).append("\r\n")
            groups.forEach { append(row(it).joinToString(",")).append("\r\n") }
        }

    private fun row(group: ProviderGroup): List<String> =
        listOf(
            text(group.key.primaryProvider),
            group.key.hasGms
                ?.toString()
                .orEmpty(),
            text(group.key.rom),
            group.key.androidSdk
                ?.toString()
                .orEmpty(),
            group.sessions.toString(),
            group.lowSample.toString(),
            number(group.trackedHours),
            number(group.acceptedRatio),
            number(group.intervalP50S),
            number(group.intervalP95S),
            number(group.accuracyP50M),
            number(group.accuracyP95M),
            number(group.batteryDrainPctPerHour),
            number(group.gapsPerHour),
            number(group.dozeGapsPerHour),
        )

    /** Never in exponent form: 0.0001, not 1.0E-4. */
    private fun number(value: Double?): String =
        value
            ?.let {
                BigDecimal
                    .valueOf(it)
                    .stripTrailingZeros()
                    .toPlainString()
            }.orEmpty()

    private fun text(value: String?): String {
        if (value == null) return ""
        val safe = if (value.firstOrNull() in FORMULA_START) "'$value" else value
        val quoted = safe.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
        return if (quoted) "\"" + safe.replace("\"", "\"\"") + "\"" else safe
    }
}
