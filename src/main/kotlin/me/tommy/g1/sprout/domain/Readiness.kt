package me.tommy.g1.sprout.domain

import io.fabric8.kubernetes.api.model.apps.Deployment

class Readiness(
    val ready: Boolean,
    val readyReplicas: Int,
    val reason: Reason,
    val message: String,
)

fun Deployment.readiness(): Readiness {
    val desired = spec.replicas ?: 1
    val current = status
    val ready =
        current != null &&
            (current.observedGeneration ?: 0) >= metadata.generation &&
            (current.updatedReplicas ?: 0) >= desired &&
            (current.availableReplicas ?: 0) >= desired
    val readyReplicas = current?.readyReplicas ?: 0
    return if (ready) {
        Readiness(true, readyReplicas, Reason.WorkloadsAvailable, "all replicas are available")
    } else {
        Readiness(false, readyReplicas, Reason.Progressing, "waiting for deployment to become available")
    }
}
