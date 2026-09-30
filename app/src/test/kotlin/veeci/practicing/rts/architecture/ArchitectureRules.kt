package veeci.practicing.rts.architecture

import com.lemonappdev.konsist.api.container.KoScope

/**
 * Architecture rules from docs/architecture/overview.md, written as functions that return violations
 * ("<file>: <import>") instead of asserting. That lets the same rule be checked against production code
 * (must be empty) and against fixture files that break it on purpose (must find exactly those).
 */
object ArchitectureRules {
    const val ROOT = "veeci.practicing.rts"
    private const val WIRING_RANK = 3

    /** Bounded contexts. Adding a context here is all it takes for every rule to cover it. */
    val contexts = listOf("identity", "tracking", "trip", "routing", "devicecontrol", "live", "telemetry", "fleet")

    private val frameworkPrefixes =
        listOf("io.ktor.", "org.jetbrains.exposed.", "java.sql.", "javax.sql.", "org.koin.", "com.zaxxer.", "org.flywaydb.")

    /** TC-0-ARCH-01: domain code is pure Kotlin — no web, database or DI framework imports. */
    fun domainFrameworkImports(scope: KoScope): List<String> =
        violations(scope) { pkg, import ->
            isDomain(pkg) && frameworkPrefixes.any { import.startsWith(it) }
        }

    /** TC-0-ARCH-02: the shared kernel depends on no context. */
    fun sharedKernelImportsContext(scope: KoScope): List<String> =
        violations(scope) { pkg, import ->
            pkg.isIn("$ROOT.shared") && contextOf(import) != null
        }

    /** TC-0-ARCH-03: another context may only be reached through its `application.api` or `domain.event` packages. */
    fun crossContextInternalImports(scope: KoScope): List<String> =
        violations(scope) { pkg, import ->
            val from = contextOf(pkg)
            val to = contextOf(import)
            from != null && to != null && from != to && !isPublicPartOf(import, to)
        }

    /** TC-0-ARCH-04: inside a context, dependencies point inward: adapter → application → domain. */
    fun layerDirectionViolations(scope: KoScope): List<String> =
        violations(scope) { pkg, import ->
            val ctx = contextOf(pkg)
            ctx != null &&
                ctx == contextOf(import) &&
                layerRank(import, ctx) > layerRank(pkg, ctx)
        }

    private fun violations(
        scope: KoScope,
        isViolation: (pkg: String, import: String) -> Boolean,
    ): List<String> =
        scope.files.flatMap { file ->
            val pkg = file.packagee?.name ?: return@flatMap emptyList()
            file.imports
                .map { it.name }
                .filter { isViolation(pkg, it) }
                .map { "${file.name}: $it" }
        }

    private fun contextOf(name: String): String? = contexts.firstOrNull { name.isIn("$ROOT.$it") }

    private fun isDomain(pkg: String): Boolean = contextOf(pkg)?.let { pkg.isIn("$ROOT.$it.domain") } ?: false

    private fun isPublicPartOf(
        import: String,
        ctx: String,
    ): Boolean = import.isIn("$ROOT.$ctx.application.api") || import.isIn("$ROOT.$ctx.domain.event")

    /** 0 = domain, 1 = application, 2 = adapter, 3 = module wiring (`<ctx>` root package), which may see every layer. */
    private fun layerRank(
        name: String,
        ctx: String,
    ): Int =
        when {
            name.isIn("$ROOT.$ctx.domain") -> 0
            name.isIn("$ROOT.$ctx.application") -> 1
            name.isIn("$ROOT.$ctx.adapter") -> 2
            else -> WIRING_RANK
        }

    private fun String.isIn(pkg: String): Boolean = this == pkg || startsWith("$pkg.")
}
