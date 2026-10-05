package veeci.practicing.rts.platform.observability

import io.ktor.http.ContentType
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.metrics.micrometer.MicrometerMetrics
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.micrometer.core.instrument.binder.jvm.JvmGcMetrics
import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics
import io.micrometer.core.instrument.binder.jvm.JvmThreadMetrics
import io.micrometer.core.instrument.binder.system.ProcessorMetrics
import io.micrometer.core.instrument.binder.system.UptimeMetrics
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry

private val prometheusText = ContentType.parse("text/plain; version=0.0.4; charset=utf-8")

/**
 * Times every HTTP request (count, duration, status, route) and exposes all metrics at `/metrics` in the
 * Prometheus text format. Prometheus pulls that page every few seconds; Grafana draws graphs and alerts from it.
 */
fun Application.configureMetrics(registry: PrometheusMeterRegistry) {
    install(MicrometerMetrics) {
        this.registry = registry
        // Becomes http_server_requests_seconds_* in Prometheus, the name standard dashboards look for.
        metricName = "http.server.requests"
        meterBinders = listOf(JvmMemoryMetrics(), JvmGcMetrics(), JvmThreadMetrics(), ProcessorMetrics(), UptimeMetrics())
    }

    routing {
        get("/metrics") { call.respondText(registry.scrape(), prometheusText) }
    }
}
