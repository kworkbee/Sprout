package me.tommy.g1.sprout.reconcile

import me.tommy.g1.sprout.api.PreviewEnvironment
import me.tommy.g1.sprout.config.SproutProperties
import me.tommy.g1.sprout.domain.READY
import me.tommy.g1.sprout.domain.Readiness
import me.tommy.g1.sprout.domain.Reason
import me.tommy.g1.sprout.domain.ingressEnabled
import me.tommy.g1.sprout.domain.namespaceName
import me.tommy.g1.sprout.domain.previewUrl
import me.tommy.g1.sprout.domain.setCondition
import java.time.Instant

class EnvironmentStatus(
    private val env: PreviewEnvironment,
    private val now: Instant,
    ingress: SproutProperties.Ingress,
) {
    private val status = env.status
    private val generation = env.metadata.generation
    private val previousReason = status.conditions.firstOrNull { it.type == READY }?.reason

    init {
        status.observedGeneration = generation
        status.namespace = env.namespaceName
        status.url = if (env.ingressEnabled) env.previewUrl(ingress.domain, ingress.port) else null
    }

    fun wasReason(reason: Reason) = previousReason == reason.name

    fun recordExpiry(expiresAt: Instant?) {
        status.expiresAt = expiresAt?.toString()
    }

    fun recordNotReady(
        reason: Reason,
        message: String,
    ) = status.conditions.setCondition(READY, false, reason, message, generation, now)

    fun recordReadiness(readiness: Readiness) {
        status.readyReplicas = readiness.readyReplicas
        status.conditions.setCondition(READY, readiness.ready, readiness.reason, readiness.message, generation, now)
    }
}
