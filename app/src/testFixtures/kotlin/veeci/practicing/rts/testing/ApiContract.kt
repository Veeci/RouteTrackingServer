package veeci.practicing.rts.testing

import com.atlassian.oai.validator.OpenApiInteractionValidator
import com.atlassian.oai.validator.model.Request
import com.atlassian.oai.validator.model.SimpleResponse
import com.atlassian.oai.validator.report.ValidationReport
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.request
import io.ktor.http.HttpHeaders

/** Checks real responses against the OpenAPI contract in `resources/openapi/rts-v1.yaml`. */
object ApiContract {
    private val validator: OpenApiInteractionValidator by lazy {
        val spec = checkNotNull(ApiContract::class.java.getResource("/openapi/rts-v1.yaml")) { "contract not on classpath" }
        OpenApiInteractionValidator.createForInlineApiSpecification(spec.readText()).build()
    }

    fun validate(
        method: String,
        path: String,
        status: Int,
        contentType: String?,
        body: String,
    ): ValidationReport {
        val response = SimpleResponse.Builder.status(status).withBody(body)
        contentType?.let { response.withContentType(it) }
        return validator.validateResponse(path, Request.Method.valueOf(method), response.build())
    }
}

/** Fails the test when this response (status, headers, body) is not what the contract promises for its request. */
suspend fun HttpResponse.shouldMatchContract() {
    val report =
        ApiContract.validate(
            request.method.value,
            request.url.encodedPath,
            status.value,
            headers[HttpHeaders.ContentType],
            bodyAsText(),
        )
    withClue(report.messages.joinToString("\n")) { report.hasErrors() shouldBe false }
}
