package veeci.practicing.rts.testing

import java.io.InputStream

/** Loads files from `src/testFixtures/resources` (GPX routes, OSM graphs, golden JSON). */
object Fixtures {
    fun text(path: String): String =
        requireNotNull(Fixtures::class.java.classLoader.getResource(path)) { "Missing test fixture: $path" }
            .readText()

    fun stream(path: String): InputStream =
        requireNotNull(Fixtures::class.java.classLoader.getResourceAsStream(path)) { "Missing test fixture: $path" }
}
