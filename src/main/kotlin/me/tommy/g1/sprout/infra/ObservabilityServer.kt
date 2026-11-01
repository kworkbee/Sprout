package me.tommy.g1.sprout.infra

import com.sun.net.httpserver.HttpServer
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import java.net.InetSocketAddress

private const val HTTP_OK = 200
private const val HTTP_UNAVAILABLE = 503

class ObservabilityServer(
    private val registry: PrometheusMeterRegistry,
    port: Int,
) {
    @Volatile
    private var ready = false
    private val server = HttpServer.create(InetSocketAddress(port), 0)

    val boundPort: Int get() = server.address.port

    init {
        serve("/metrics", "text/plain; version=0.0.4") { HTTP_OK to registry.scrape() }
        serve("/healthz") { HTTP_OK to "ok" }
        serve("/readyz") { if (ready) HTTP_OK to "ok" else HTTP_UNAVAILABLE to "starting" }
    }

    fun start() = server.start()

    fun markReady() {
        ready = true
    }

    fun stop() {
        ready = false
        server.stop(0)
    }

    private fun serve(
        path: String,
        type: String = "text/plain",
        handler: () -> Pair<Int, String>,
    ) {
        server.createContext(path) { exchange ->
            val (code, body) = handler()
            val bytes = body.toByteArray()
            exchange.responseHeaders.add("Content-Type", type)
            exchange.sendResponseHeaders(code, bytes.size.toLong())
            exchange.responseBody.write(bytes)
            exchange.close()
        }
    }
}
