package veeci.practicing.rts.testing

/** Loads files from `src/testFixtures/resources` (GPX routes, OSM graphs, golden JSON). */
object Fixtures {
    fun text(path: String): String =
        requireNotNull(Fixtures::class.java.classLoader.getResource(path)) { "Missing test fixture: $path" }
            .readText()
}
