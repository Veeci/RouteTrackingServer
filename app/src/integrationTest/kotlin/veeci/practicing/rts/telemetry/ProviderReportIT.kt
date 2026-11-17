package veeci.practicing.rts.telemetry

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import org.koin.ktor.ext.get
import org.testcontainers.junit.jupiter.Testcontainers
import veeci.practicing.rts.platform.db.TransactionRunner
import veeci.practicing.rts.protocol.DeviceDto
import veeci.practicing.rts.protocol.LocationProviderDto
import veeci.practicing.rts.telemetry.adapter.inbound.web.ProviderGroupDto
import veeci.practicing.rts.telemetry.adapter.inbound.web.ProviderReportResponse
import veeci.practicing.rts.telemetry.application.port.out.SessionHealthRepository
import veeci.practicing.rts.telemetry.domain.DeviceKey
import veeci.practicing.rts.telemetry.domain.SessionHealth
import veeci.practicing.rts.telemetry.domain.SessionHealthRecord
import veeci.practicing.rts.testing.SocketTestApp
import veeci.practicing.rts.testing.TestTokens
import veeci.practicing.rts.testing.shouldMatchContract
import veeci.practicing.rts.testing.socketApp
import java.util.UUID
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * The provider report over HTTP (phase 9c). The database is shared with other tests, so each test stores its
 * sessions in a time range of its own, in 2020, or gives its devices a ROM no other test uses.
 */
@Testcontainers(disabledWithoutDocker = true)
class ProviderReportIT {
    private val json = Json { ignoreUnknownKeys = true }
    private val admin = TestTokens.admin()

    /** A one-day range in 2020 that no other test writes to. */
    private val day = Instant.parse("2020-01-01T00:00:00Z") + Random.nextInt(0, 360).days

    private fun SocketTestApp.store(
        provider: String,
        device: DeviceKey,
        startedAt: Instant = day + 1.hours,
    ) = runBlocking {
        val health =
            SessionHealth(10, 0, 1.0, emptyMap(), 5.0, 6.0, 4.0, 8.0, emptyList(), 2.5, mapOf(provider to 1.0), provider)
        val record = SessionHealthRecord(UUID.randomUUID(), null, startedAt, startedAt + 1.hours, device, startedAt + 2.hours, health)
        application.get<TransactionRunner>().inTransaction { application.get<SessionHealthRepository>().save(record) }
    }

    private suspend fun SocketTestApp.report(
        from: Instant,
        to: Instant,
        token: String = admin,
        csv: Boolean = false,
    ): HttpResponse = client.get("/api/v1/admin/reports/providers${if (csv) ".csv" else ""}?from=$from&to=$to") { bearerAuth(token) }

    private suspend fun HttpResponse.groups(): List<ProviderGroupDto> =
        json.decodeFromString(ProviderReportResponse.serializer(), bodyAsText()).groups

    private val gms = DeviceKey("Google", "Pixel 7", 35, null, hasGms = true)
    private val aosp = DeviceKey("Google", "Pixel 7", 35, "LineageOS 22.1", hasGms = false)

    @Test
    fun `TC-9-RPT-01 3 GMS and 2 AOSP sessions make two groups, and the AOSP group is a low sample`() =
        socketApp {
            repeat(3) { store("GMS_FUSED", gms) }
            repeat(2) { store("AOSP_GPS", aosp) }

            val response = report(day, day + 1.days)

            response.shouldMatchContract()
            val groups = response.groups()
            groups.map { Triple(it.primaryProvider, it.sessions, it.lowSample) } shouldBe
                listOf(Triple("AOSP_GPS", 2, true), Triple("GMS_FUSED", 3, false))
            groups[0].rom shouldBe "LineageOS 22.1"
            groups[1].intervalP50S shouldBe 5.0
        }

    @Test
    fun `TC-9-RPT-02 the range holds sessions that started from from, up to but not at to`() =
        socketApp {
            store("GMS_FUSED", gms, startedAt = day - 1.seconds)
            store("GMS_FUSED", gms, startedAt = day)
            store("GMS_FUSED", gms, startedAt = day + 1.days - 1.seconds)
            store("GMS_FUSED", gms, startedAt = day + 1.days)

            report(day, day + 1.days).groups().single().sessions shouldBe 2
        }

    @Test
    fun `TC-9-RPT-03 the CSV has a header row and one row per group, with dot decimals`() =
        socketApp {
            repeat(2) { store("GMS_FUSED", gms) }
            store("AOSP_GPS", aosp)

            val response = report(day, day + 1.days, csv = true)

            response.status shouldBe HttpStatusCode.OK
            response.headers[HttpHeaders.ContentType]!! shouldStartWith "text/csv"
            response.headers[HttpHeaders.ContentDisposition]!! shouldContain "attachment"
            val rows = response.bodyAsText().trimEnd().split("\r\n")
            rows shouldHaveSize 3
            rows[0] shouldStartWith "primaryProvider,hasGms,rom,androidSdk,sessions"
            rows[1] shouldBe "AOSP_GPS,false,LineageOS 22.1,35,1,true,1,1,5,6,4,8,2.5,0,0"
        }

    @Test
    fun `TC-9-RPT-04 a driver or a guest gets 403 on both reports, and a bad range is 400`() =
        socketApp {
            listOf(TestTokens.driver(), TestTokens.guest()).forEach { token ->
                listOf(false, true).forEach { csv ->
                    report(day, day + 1.days, token, csv).apply {
                        status shouldBe HttpStatusCode.Forbidden
                        bodyAsText() shouldContain "SESSION_HEALTH_FORBIDDEN"
                    }
                }
            }
            report(day, day).apply {
                status shouldBe HttpStatusCode.BadRequest
                shouldMatchContract()
            }
            report(day, day + 32.days).status shouldBe HttpStatusCode.BadRequest
            client.get("/api/v1/admin/reports/providers?from=yesterday&to=$day") { bearerAuth(admin) }.status shouldBe
                HttpStatusCode.BadRequest
        }

    @Test
    fun `TC-9-E2E-01 an AOSP session with a Doze gap shows more gaps per hour than a GMS session`() =
        socketApp {
            val tag = UUID.randomUUID().toString().take(8) // ROM names no other test uses
            val since = Clock.System.now() - 1.minutes
            val start = Clock.System.now() - 10.minutes

            val steady = (0 until 24).map { fixAt(start + (it * 5).seconds, it, LocationProviderDto.GMS_FUSED) }
            val gms = recordSession(DeviceDto("Google", "Pixel 7", 35, "stock-$tag", hasGms = true), steady)

            // 12 fixes, 6 minutes of nothing while the phone lies still, then 12 more.
            val gapEnd = start + 55.seconds + 6.minutes
            val dozing =
                (0 until 12).map { fixAt(start + (it * 5).seconds, it, LocationProviderDto.AOSP_GPS) } +
                    (0 until 12).map { fixAt(gapEnd + (it * 5).seconds, 12 + it, LocationProviderDto.AOSP_GPS) }
            val still = listOf(statusAt(start + 50.seconds, "STILL"), statusAt(gapEnd, "STILL"))
            val aosp = recordSession(DeviceDto("Google", "Pixel 7", 35, "aosp-$tag", hasGms = false), dozing, still)
            computeHealth(gms)
            computeHealth(aosp)

            val groups = report(since, Clock.System.now() + 1.minutes).groups().filter { it.rom?.endsWith(tag) == true }

            val (aospGroup, gmsGroup) =
                groups
                    .partition {
                        it.primaryProvider == "AOSP_GPS"
                    }.let { it.first.single() to it.second.single() }
            gmsGroup.gapsPerHour shouldBe 0.0
            aospGroup.gapsPerHour!! shouldBeGreaterThan gmsGroup.gapsPerHour!!
            aospGroup.dozeGapsPerHour!! shouldBeGreaterThan 0.0
        }
}
