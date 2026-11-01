package me.tommy.g1.sprout.infra

import io.fabric8.kubernetes.api.model.ConfigMapBuilder
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import me.tommy.g1.sprout.TestCluster
import me.tommy.g1.sprout.domain.EventType
import me.tommy.g1.sprout.domain.Reason
import me.tommy.g1.sprout.previewEnvironment

class EventsSpec :
    FunSpec({
        test("events are best effort: a failing client is only logged") {
            TestCluster.newClient().also { it.close() }.let { closed ->
                KubernetesEventPublisher(closed)
                    .emit(previewEnvironment("any"), EventType.Warning, Reason.InvalidTTL, "client is closed")
            }
        }

        test("an event about a namespaced object lands in its namespace") {
            val client = TestCluster.newClient()
            val configMap =
                client
                    .configMaps()
                    .inNamespace("default")
                    .resource(
                        ConfigMapBuilder()
                            .withNewMetadata()
                            .withName("evt")
                            .withNamespace("default")
                            .endMetadata()
                            .build(),
                    ).create()
            KubernetesEventPublisher(client).emit(configMap, EventType.Normal, Reason.TTLExpired, "namespaced object")
            client
                .v1()
                .events()
                .inNamespace("default")
                .withName("evt.ttlexpired")
                .get()
                .shouldNotBeNull()
        }
    })
