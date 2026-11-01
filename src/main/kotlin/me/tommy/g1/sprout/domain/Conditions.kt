package me.tommy.g1.sprout.domain

import io.fabric8.kubernetes.api.model.Condition
import java.time.Instant

const val READY = "Ready"

fun MutableList<Condition>.setCondition(
    type: String,
    status: Boolean,
    reason: Reason,
    message: String,
    generation: Long?,
    now: Instant = Instant.now(),
) {
    val statusText = if (status) "True" else "False"
    val previous = firstOrNull { it.type == type }
    removeAll { it.type == type }
    add(
        Condition().apply {
            this.type = type
            this.status = statusText
            this.reason = reason.name
            this.message = message
            observedGeneration = generation
            lastTransitionTime = if (previous?.status == statusText) previous.lastTransitionTime else now.toString()
        },
    )
}
