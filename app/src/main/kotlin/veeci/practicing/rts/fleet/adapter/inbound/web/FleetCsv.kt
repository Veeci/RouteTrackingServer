package veeci.practicing.rts.fleet.adapter.inbound.web

import veeci.practicing.rts.fleet.application.FleetRow
import java.math.BigDecimal

/**
 * The driver list as CSV (RFC 4180), with the rules of the provider report (phase 9c): a header row, CRLF line ends,
 * numbers as plain decimals with a `.`, an empty cell for a missing value, and a leading `'` on a text cell that a
 * spreadsheet would run as a formula (it starts with `=`, `+`, `-` or `@`). Display names are typed by people, so
 * they are untrusted.
 */
object FleetCsv {
    const val CONTENT_TYPE = "text/csv"

    private val HEADER =
        listOf(
            "driverId",
            "displayName",
            "enabled",
            "online",
            "lastSeenAt",
            "heardAt",
            "lat",
            "lng",
            "speedMps",
            "bearingDeg",
            "batteryPct",
            "isCharging",
            "motion",
            "currentTripId",
            "tripStatus",
        )

    private val FORMULA_START = setOf('=', '+', '-', '@')
    private const val LINE_END = "\r\n"

    fun header(): String = HEADER.joinToString(",") + LINE_END

    fun row(row: FleetRow): String {
        val driver = row.driver
        return listOf(
            driver.driverId.toString(),
            text(driver.displayName),
            driver.enabled.toString(),
            row.online.toString(),
            driver.lastSeenAt?.toString().orEmpty(),
            driver.heardAt?.toString().orEmpty(),
            number(driver.position?.lat),
            number(driver.position?.lng),
            number(driver.position?.speedMps),
            number(driver.position?.bearingDeg),
            driver.device
                ?.batteryPct
                ?.toString()
                .orEmpty(),
            driver.device
                ?.isCharging
                ?.toString()
                .orEmpty(),
            text(driver.device?.motion),
            driver.trip
                ?.currentTripId
                ?.toString()
                .orEmpty(),
            text(driver.trip?.status),
        ).joinToString(",") + LINE_END
    }

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
