package veeci.practicing.rts.testing

import io.kotest.matchers.shouldBe
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import veeci.practicing.rts.platform.http.Problem
import veeci.practicing.rts.platform.http.ProblemContentType

/** Asserts the response uses the problem media type, then decodes its body. */
suspend fun HttpResponse.problem(): Problem {
    contentType()?.withoutParameters() shouldBe ProblemContentType
    return Json.decodeFromString(bodyAsText())
}
