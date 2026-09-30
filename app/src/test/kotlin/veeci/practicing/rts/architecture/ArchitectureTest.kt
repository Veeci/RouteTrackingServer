package veeci.practicing.rts.architecture

import com.lemonappdev.konsist.api.Konsist
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class ArchitectureTest {
    private val production = Konsist.scopeFromProduction(moduleName = "app")
    private val fixtures = Konsist.scopeFromDirectory("app/src/test/resources/architecture-fixtures")

    /** The real code must satisfy every rule. */
    @Nested
    inner class ProductionCode {
        /** TC-0-ARCH-01 */
        @Test
        fun `domain does not import frameworks`() {
            ArchitectureRules.domainFrameworkImports(production).shouldBeEmpty()
        }

        /** TC-0-ARCH-02 */
        @Test
        fun `shared kernel does not import contexts`() {
            ArchitectureRules.sharedKernelImportsContext(production).shouldBeEmpty()
        }

        /** TC-0-ARCH-03 */
        @Test
        fun `contexts use only each other's public api and events`() {
            ArchitectureRules.crossContextInternalImports(production).shouldBeEmpty()
        }

        /** TC-0-ARCH-04 */
        @Test
        fun `dependencies point inward inside a context`() {
            ArchitectureRules.layerDirectionViolations(production).shouldBeEmpty()
        }
    }

    /** Fixture files that break the rules on purpose prove each rule detects what it claims to. */
    @Nested
    inner class RulesDetectViolations {
        @Test
        fun `framework import in domain is reported`() {
            ArchitectureRules.domainFrameworkImports(fixtures) shouldContainExactlyInAnyOrder
                listOf("LeakyTrip: io.ktor.server.routing.Route", "LeakyTrip: org.jetbrains.exposed.v1.core.Table")
        }

        @Test
        fun `context import in shared kernel is reported`() {
            ArchitectureRules.sharedKernelImportsContext(fixtures) shouldContainExactlyInAnyOrder
                listOf("LeakyShared: veeci.practicing.rts.trip.domain.Trip")
        }

        @Test
        fun `internal import across contexts is reported, public api and events are not`() {
            ArchitectureRules.crossContextInternalImports(fixtures) shouldContainExactlyInAnyOrder
                listOf("LeakyLive: veeci.practicing.rts.trip.adapter.out.persistence.TripsTable")
        }

        @Test
        fun `outward import inside a context is reported`() {
            ArchitectureRules.layerDirectionViolations(fixtures) shouldContainExactlyInAnyOrder
                listOf(
                    "LeakyTrip: veeci.practicing.rts.trip.application.TripService",
                    "LeakyTripService: veeci.practicing.rts.trip.adapter.out.persistence.ExposedTripRepository",
                )
        }
    }
}
