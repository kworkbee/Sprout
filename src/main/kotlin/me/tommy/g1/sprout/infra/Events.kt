package me.tommy.g1.sprout.infra

import io.fabric8.kubernetes.api.model.Event
import io.fabric8.kubernetes.api.model.EventSource
import io.fabric8.kubernetes.api.model.HasMetadata
import io.fabric8.kubernetes.api.model.ObjectMeta
import io.fabric8.kubernetes.api.model.ObjectReference
import io.fabric8.kubernetes.client.KubernetesClient
import me.tommy.g1.sprout.domain.EventType
import me.tommy.g1.sprout.domain.FIELD_MANAGER
import me.tommy.g1.sprout.domain.Reason
import org.slf4j.LoggerFactory
import java.time.Instant

interface EventPublisher {
    fun emit(
        obj: HasMetadata,
        type: EventType,
        reason: Reason,
        message: String,
    )
}

class KubernetesEventPublisher(
    private val client: KubernetesClient,
) : EventPublisher {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun emit(
        obj: HasMetadata,
        type: EventType,
        reason: Reason,
        message: String,
    ) {
        runCatching {
            client
                .resource(event(obj, type, reason, message))
                .forceConflicts()
                .fieldManager(FIELD_MANAGER)
                .serverSideApply()
        }.onFailure { log.warn("could not record event {}: {}", reason, it.message) }
    }

    private fun event(
        obj: HasMetadata,
        eventType: EventType,
        eventReason: Reason,
        text: String,
    ): Event {
        val now = Instant.now().toString()
        return Event().apply {
            metadata =
                ObjectMeta().apply {
                    name = "${obj.metadata.name}.${eventReason.name.lowercase()}"
                    namespace = obj.metadata.namespace ?: "default"
                }
            involvedObject =
                ObjectReference().apply {
                    apiVersion = obj.apiVersion
                    kind = obj.kind
                    name = obj.metadata.name
                    uid = obj.metadata.uid
                }
            type = eventType.name
            reason = eventReason.name
            message = text
            firstTimestamp = now
            lastTimestamp = now
            source = EventSource().apply { component = FIELD_MANAGER }
        }
    }
}
