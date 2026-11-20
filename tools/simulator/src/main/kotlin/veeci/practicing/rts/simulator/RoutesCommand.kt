package veeci.practicing.rts.simulator

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import veeci.practicing.rts.protocol.ProtocolJson
import veeci.practicing.rts.simulator.routes.RouteLoad
import veeci.practicing.rts.simulator.routes.RouteLoadOptions

/** A new user from the dev token endpoint, as `userId to token`, or null after printing why not. */
internal suspend fun devToken(
    url: String,
    role: String,
    displayName: String? = null,
): Pair<String, String>? {
    val name = displayName?.let { ""","displayName":"$it"""" } ?: ""
    HttpClient(CIO).use { http ->
        val response =
            http.post("$url/api/v1/dev/tokens") {
                contentType(ContentType.Application.Json)
                setBody("""{"role":"$role"$name}""")
            }
        if (!response.status.isSuccess()) return null.also { println("${response.status}: ${response.bodyAsText()}") }
        val body = ProtocolJson.parseToJsonElement(response.bodyAsText()).jsonObject
        return body.getValue("userId").jsonPrimitive.content to body.getValue("token").jsonPrimitive.content
    }
}

internal suspend fun routes(values: Map<String, String>): Boolean {
    val url = values["url"] ?: "http://localhost:8080"
    val token = values["token"] ?: devToken(url, "GUEST", "route load test")?.second ?: return false
    val defaults = RouteLoadOptions(token = token)
    val options =
        defaults.copy(
            baseUrl = url,
            rps = values["rps"]?.toInt() ?: defaults.rps,
            duration = values["duration"]?.let(::duration) ?: defaults.duration,
            bbox = values["bbox"]?.split(",")?.map { it.trim().toDouble() } ?: defaults.bbox,
            p95Limit = values["p95-limit"]?.let(::duration) ?: defaults.p95Limit,
            seed = values["seed"]?.toLong() ?: defaults.seed,
        )
    println("Sending ${options.rps} route requests a second to $url for ${options.duration}, seed ${options.seed}")
    val report = RouteLoad.httpClient(options.rps).use { http -> RouteLoad(http, options).run() }
    report.lines().forEach(::println)
    return report.passed
}
