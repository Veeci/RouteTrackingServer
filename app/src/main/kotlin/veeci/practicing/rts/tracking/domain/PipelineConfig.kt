package veeci.practicing.rts.tracking.domain

import veeci.practicing.rts.shared.geo.Meters
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

/** The limits that the fix pipeline applies. The defaults suit a car in a city. */
data class PipelineConfig(
    val maxAccuracy: Meters = Meters(value = 50.0),
    val maxClockSkew: Duration = 30.seconds,
    val maxAge: Duration = 24.hours,
    /** 70 m/s is 252 km/h: faster than any car, so a faster jump is a GPS error. */
    val maxSpeedMps: Double = 70.0,
    val rejectMock: Boolean = true,
)
