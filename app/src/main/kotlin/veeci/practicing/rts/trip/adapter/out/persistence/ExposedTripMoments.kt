package veeci.practicing.rts.trip.adapter.out.persistence

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.datetime.CurrentTimestamp
import org.jetbrains.exposed.v1.datetime.timestamp
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.json.jsonb
import veeci.practicing.rts.trip.application.port.out.RuleStateStore
import veeci.practicing.rts.trip.application.port.out.TripMomentRepository
import veeci.practicing.rts.trip.domain.ArrivalState
import veeci.practicing.rts.trip.domain.BatteryState
import veeci.practicing.rts.trip.domain.TripId
import veeci.practicing.rts.trip.domain.event.TripMoment
import veeci.practicing.rts.trip.domain.event.TripMomentRaisedV1
import kotlin.time.Instant

/** The `trip_moments` table (migration V8). */
internal object TripMomentsTable : Table("trip_moments") {
    val id = long("id").autoIncrement()
    val tripId = javaUUID("trip_id")
    val kind = text("kind")
    val payload = jsonb("payload", Json, JsonElement.serializer())
    val at = timestamp("at")
    override val primaryKey = PrimaryKey(id)
}

/** The `trip_rule_state` table (migration V8). */
internal object TripRuleStateTable : Table("trip_rule_state") {
    val tripId = javaUUID("trip_id")
    val rule = text("rule")
    val state = jsonb("state", Json, JsonElement.serializer())
    val updatedAt = timestamp("updated_at").defaultExpression(CurrentTimestamp)
    override val primaryKey = PrimaryKey(tripId, rule)
}

/** Runs inside the caller's transaction; it opens none of its own. */
class ExposedTripMomentRepository : TripMomentRepository {
    override suspend fun append(moment: TripMoment) {
        val payload = TripMomentRaisedV1.of(moment)
        TripMomentsTable.insert {
            it[tripId] = moment.tripId
            it[kind] = payload.kind
            it[TripMomentsTable.payload] = Json.encodeToJsonElement(TripMomentRaisedV1.serializer(), payload)
            it[at] = moment.at
        }
    }
}

/**
 * Runs inside the caller's transaction; a lock lasts until that transaction ends. One row per (trip, rule); the
 * state is stored as JSON in its own serializable form, so the domain state stays free of serialization.
 */
class ExposedRuleStateStore : RuleStateStore {
    override suspend fun lockArrival(trip: TripId): ArrivalState =
        lock(trip, ARRIVAL, StoredArrivalState.serializer(), StoredArrivalState()).toState()

    override suspend fun saveArrival(
        trip: TripId,
        state: ArrivalState,
    ) = save(trip, ARRIVAL, StoredArrivalState.serializer(), StoredArrivalState.of(state))

    override suspend fun lockBattery(trip: TripId): BatteryState =
        lock(trip, BATTERY, StoredBatteryState.serializer(), StoredBatteryState()).toState()

    override suspend fun saveBattery(
        trip: TripId,
        state: BatteryState,
    ) = save(trip, BATTERY, StoredBatteryState.serializer(), StoredBatteryState.of(state))

    private fun <S> lock(
        trip: TripId,
        rule: String,
        serializer: KSerializer<S>,
        empty: S,
    ): S {
        // Creating the row first gives FOR UPDATE a row to lock even for a trip's first batch.
        TripRuleStateTable.insertIgnore {
            it[tripId] = trip.value
            it[TripRuleStateTable.rule] = rule
            it[state] = Json.encodeToJsonElement(serializer, empty)
        }
        val stored =
            TripRuleStateTable
                .selectAll()
                .where { (TripRuleStateTable.tripId eq trip.value) and (TripRuleStateTable.rule eq rule) }
                .forUpdate()
                .single()[TripRuleStateTable.state]
        return Json.decodeFromJsonElement(serializer, stored)
    }

    private fun <S> save(
        trip: TripId,
        rule: String,
        serializer: KSerializer<S>,
        value: S,
    ) {
        TripRuleStateTable.update({ (TripRuleStateTable.tripId eq trip.value) and (TripRuleStateTable.rule eq rule) }) {
            it[state] = Json.encodeToJsonElement(serializer, value)
            it[updatedAt] = CurrentTimestamp
        }
    }

    /** Fields may be added with defaults. */
    @Serializable
    private data class StoredArrivalState(
        val near: Int = 0,
        val far: Int = 0,
        val announced: Boolean = false,
        val atPickup: Int = 0,
        val slowSince: Instant? = null,
        val lastAt: Instant? = null,
    ) {
        fun toState() = ArrivalState(near, far, announced, atPickup, slowSince, lastAt)

        companion object {
            fun of(state: ArrivalState) =
                StoredArrivalState(state.near, state.far, state.announced, state.atPickup, state.slowSince, state.lastAt)
        }
    }

    /** Fields may be added with defaults. */
    @Serializable
    private data class StoredBatteryState(
        val announced: Boolean = false,
        val lastAt: Instant? = null,
    ) {
        fun toState() = BatteryState(announced, lastAt)

        companion object {
            fun of(state: BatteryState) = StoredBatteryState(state.announced, state.lastAt)
        }
    }

    private companion object {
        const val ARRIVAL = "arrival"
        const val BATTERY = "battery"
    }
}
