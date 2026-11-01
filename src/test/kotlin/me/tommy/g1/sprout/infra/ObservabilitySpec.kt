package me.tommy.g1.sprout.infra

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

class ObservabilitySpec :
    FunSpec({
        test("probes and metrics are served") {
            val registry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
            SproutMetrics(registry).expired.increment()
            val server = ObservabilityServer(registry, 0)
            server.start()
            val http = HttpClient.newHttpClient()

            fun get(path: String) =
                http.send(
                    HttpRequest.newBuilder(URI("http://localhost:${server.boundPort}$path")).build(),
                    HttpResponse.BodyHandlers.ofString(),
                )

            get("/healthz").statusCode() shouldBe 200
            get("/readyz").statusCode() shouldBe 503
            server.markReady()
            get("/readyz").statusCode() shouldBe 200
            get("/metrics").body() shouldContain "sprout_environments_expired_total"
            server.stop()
            http.close()
        }
    })
