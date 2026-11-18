package veeci.practicing.rts.platform.http

import io.kotest.matchers.shouldBe
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/** Which peers may set the client address with X-Forwarded-For (phase 1.5, T1b.4). */
class ForwardedHeadersTest {
    @ParameterizedTest
    @ValueSource(strings = ["127.0.0.1", "127.8.9.10", "::1", "0:0:0:0:0:0:0:1", "localhost"])
    fun `a peer on loopback is a trusted proxy`(address: String) {
        isLoopback(address) shouldBe true
    }

    @ParameterizedTest
    @ValueSource(strings = ["203.0.113.7", "172.18.0.1", "10.0.0.2", "fe80::1", "example.com", "beef.cafe", "", "unknown"])
    fun `any other peer is not, and a host name is never looked up`(address: String) {
        isLoopback(address) shouldBe false
    }
}
