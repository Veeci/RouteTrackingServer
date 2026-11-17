package veeci.practicing.rts.testing

import org.testcontainers.containers.GenericContainer
import org.testcontainers.utility.DockerImageName

/** One Redis container for the whole test run (phase 10), like [TestDatabase]. */
object TestRedis {
    private val shared: GenericContainer<*> by lazy { newContainer().apply { start() } }

    /** `redis://host:port` of the shared container. */
    fun url(): String = urlOf(shared)

    /** Env vars that turn Redis on for an app under test. */
    fun env(): Map<String, String> = mapOf("REDIS_URL" to url())

    /** A container of its own, for tests that stop Redis on purpose. The caller starts and closes it. */
    fun newContainer(): GenericContainer<*> = GenericContainer(DockerImageName.parse("redis:7-alpine")).withExposedPorts(REDIS_PORT)

    fun urlOf(container: GenericContainer<*>): String = "redis://${container.host}:${container.getMappedPort(REDIS_PORT)}"

    private const val REDIS_PORT = 6379
}
