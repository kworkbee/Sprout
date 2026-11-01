package me.tommy.g1.sprout.domain

import io.fabric8.kubernetes.api.model.NamespaceBuilder
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder
import io.fabric8.kubernetes.api.model.apps.Deployment
import io.fabric8.kubernetes.api.model.apps.DeploymentSpec
import io.fabric8.kubernetes.api.model.apps.DeploymentStatus
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import me.tommy.g1.sprout.api.DEFAULT_PORT
import me.tommy.g1.sprout.manifest.desiredNamespace
import me.tommy.g1.sprout.previewEnvironment

private fun deployment(
    replicas: Int?,
    status: DeploymentStatus?,
) = Deployment().apply {
    metadata = ObjectMetaBuilder().withGeneration(2).build()
    spec = DeploymentSpec().apply { this.replicas = replicas }
    this.status = status
}

private fun status(
    observed: Long?,
    updated: Int?,
    available: Int?,
    ready: Int? = available,
) = DeploymentStatus().apply {
    observedGeneration = observed
    updatedReplicas = updated
    availableReplicas = available
    readyReplicas = ready
}

class DomainSpec :
    FunSpec({
        context("names") {
            test("port falls back to the default") {
                previewEnvironment("a").port shouldBe DEFAULT_PORT
                previewEnvironment("a", port = 80).port shouldBe 80
            }

            test("preview url follows domain and port") {
                val env = previewEnvironment("pr-1")
                env.previewHost("localhost") shouldBe "pr-1.localhost"
                env.previewUrl("localhost", 8080) shouldBe "http://pr-1.localhost:8080"
                env.previewUrl("example.dev", null) shouldBe "http://pr-1.example.dev"
            }

            test("ingress is opt-in and can be disabled") {
                previewEnvironment("a", ingress = false).ingressEnabled shouldBe false
                previewEnvironment("a").ingressEnabled shouldBe true
                previewEnvironment("a").apply { spec.ingress!!.enabled = true }.ingressEnabled shouldBe true
                previewEnvironment("a").apply { spec.ingress!!.enabled = false }.ingressEnabled shouldBe false
            }
        }

        context("ownership") {
            val env = previewEnvironment("pr-1")
            val bare =
                NamespaceBuilder()
                    .withNewMetadata()
                    .withName("sprout-pr-1")
                    .endMetadata()
                    .build()

            test("only namespaces labelled for this environment are owned") {
                env.desiredNamespace().isOwnedBy(env) shouldBe true
                previewEnvironment("pr-2").desiredNamespace().isOwnedBy(env) shouldBe false
                env.desiredNamespace().apply { metadata.labels = mapOf(MANAGED_BY to MANAGER) }.isOwnedBy(env) shouldBe
                    false
                bare.apply { metadata.labels = null }.isOwnedBy(env) shouldBe false
            }

            test("the namespace state decides whether we may proceed") {
                null.stateFor(env) shouldBe NamespaceState.USABLE
                env.desiredNamespace().stateFor(env) shouldBe NamespaceState.USABLE
                bare.stateFor(env) shouldBe NamespaceState.CONFLICT
                val terminating =
                    env.desiredNamespace().apply {
                        status =
                            io.fabric8.kubernetes.api.model
                                .NamespaceStatus()
                    }
                terminating.status.phase = "Terminating"
                terminating.stateFor(env) shouldBe NamespaceState.TERMINATING
            }
        }

        context("readiness") {
            test("a deployment is ready when the current generation is fully available") {
                deployment(1, status(2, 1, 1)).readiness().let {
                    it.ready shouldBe true
                    it.readyReplicas shouldBe 1
                    it.reason shouldBe Reason.WorkloadsAvailable
                }
                deployment(null, status(2, 1, 1)).readiness().ready shouldBe true
                deployment(1, status(2, 1, 1, null)).readiness().readyReplicas shouldBe 0
            }

            test("anything less is progressing") {
                deployment(1, null).readiness().ready shouldBe false
                deployment(1, null).readiness().readyReplicas shouldBe 0
                deployment(1, status(null, 1, 1)).readiness().ready shouldBe false
                deployment(1, status(1, 1, 1)).readiness().ready shouldBe false
                deployment(1, status(2, null, 1)).readiness().ready shouldBe false
                deployment(1, status(2, 1, null)).readiness().ready shouldBe false
                deployment(2, status(2, 1, 2)).readiness().ready shouldBe false
                deployment(2, status(2, 2, 1)).readiness().reason shouldBe Reason.Progressing
            }
        }
    })
