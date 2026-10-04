package veeci.practicing.rts.contract

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import veeci.practicing.rts.testing.ApiContract

/** The contract check must be able to fail, otherwise every "matches the contract" test proves nothing. */
class ApiContractTest {
    @Test
    fun `a response that matches the contract passes`() {
        ApiContract.validate("GET", "/health/live", 200, "application/json", """{"status":"UP"}""").hasErrors() shouldBe false
    }

    @Test
    fun `an unknown enum value is caught`() {
        ApiContract.validate("GET", "/health/live", 200, "application/json", """{"status":"MAYBE"}""").hasErrors() shouldBe true
    }

    @Test
    fun `an undocumented field is caught`() {
        ApiContract.validate("GET", "/health/live", 200, "application/json", """{"status":"UP","uptime":5}""").hasErrors() shouldBe true
    }

    @Test
    fun `an undocumented status code is caught`() {
        ApiContract.validate("GET", "/health/live", 418, "application/json", """{"status":"UP"}""").hasErrors() shouldBe true
    }

    @Test
    fun `a problem missing its code is caught`() {
        val problem = """{"type":"urn:rts:error:rate-limited","title":"Rate limited","status":429,"traceId":"t"}"""

        ApiContract.validate("GET", "/health/live", 429, "application/problem+json", problem).hasErrors() shouldBe true
    }
}
