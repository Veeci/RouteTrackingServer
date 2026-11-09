package veeci.practicing.rts.platform.http

import io.ktor.serialization.kotlinx.json.DefaultJson
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import kotlinx.serialization.json.Json

/**
 * The JSON settings of the REST API: Ktor's defaults, except that an absent optional value is left out instead of
 * being written as `null` (the contract describes optional fields as "absent", not as nullable). Code that encodes a
 * body itself (stored idempotent responses) uses the same settings, so a replay looks exactly like the original.
 */
val ApiJson: Json = Json(DefaultJson) { explicitNulls = false }

fun Application.configureSerialization() {
    install(ContentNegotiation) {
        json(ApiJson)
    }
}
