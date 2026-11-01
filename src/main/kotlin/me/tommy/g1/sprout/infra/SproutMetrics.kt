package me.tommy.g1.sprout.infra

import io.micrometer.core.instrument.Counter
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry

class SproutMetrics(
    private val registry: PrometheusMeterRegistry,
) {
    val expired = counter("sprout_environments_expired_total", "Environments deleted by TTL")
    val cleanupStuck = counter("sprout_cleanup_stuck_total", "Cleanup passes that waited for a terminating namespace")
    val conflicts = counter("sprout_namespace_conflicts_total", "Reconciles blocked by a foreign namespace")

    private fun counter(
        name: String,
        description: String,
    ): Counter = Counter.builder(name).description(description).register(registry)
}
