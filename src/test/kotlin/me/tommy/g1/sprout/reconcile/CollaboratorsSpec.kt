package me.tommy.g1.sprout.reconcile

import io.fabric8.kubernetes.api.model.SecretBuilder
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import me.tommy.g1.sprout.TestCluster
import me.tommy.g1.sprout.config.SproutProperties
import me.tommy.g1.sprout.domain.ENV_SECRET
import me.tommy.g1.sprout.domain.READY
import me.tommy.g1.sprout.domain.Readiness
import me.tommy.g1.sprout.domain.Reason
import me.tommy.g1.sprout.domain.SHAREABLE_LABEL
import me.tommy.g1.sprout.manifest.DesiredContext
import me.tommy.g1.sprout.manifest.desiredNamespace
import me.tommy.g1.sprout.manifest.guardRails
import me.tommy.g1.sprout.manifest.workloads
import me.tommy.g1.sprout.previewEnvironment
import java.time.Duration
import java.time.Instant

class CollaboratorsSpec :
    FunSpec({
        val client = TestCluster.newClient()
        val cluster = KubernetesCluster(client)
        val props = SproutProperties()

        beforeSpec {
            client
                .namespaces()
                .resource(
                    io.fabric8.kubernetes.api.model
                        .NamespaceBuilder()
                        .withNewMetadata()
                        .withName("sprout-system")
                        .endMetadata()
                        .build(),
                ).serverSideApply()
            client
                .namespaces()
                .resource(
                    previewEnvironment("sec1").desiredNamespace(),
                ).serverSideApply()
        }

        context("event routing") {
            test("a child maps to the environment named in its label") {
                val env = previewEnvironment("rt1")
                primaryOf(
                    env.desiredNamespace(),
                ).single().name shouldBe "rt1"
                val bare =
                    env
                        .desiredNamespace()
                        .apply { metadata.labels = emptyMap() }
                primaryOf(bare) shouldBe emptySet()
                primaryOf(bare.apply { metadata.labels = null }) shouldBe emptySet()
            }
        }

        context("requeue") {
            test("keeps the earliest candidate") {
                val requeue = Requeue()
                requeue.orNull().shouldBeNull()
                requeue.earliest(Duration.ofMinutes(5)) shouldBe Duration.ofMinutes(5)
                requeue.earliest(Duration.ofSeconds(3)) shouldBe Duration.ofSeconds(3)
                requeue.earliest(Duration.ofMinutes(1)) shouldBe Duration.ofSeconds(3)
                requeue.orNull() shouldBe Duration.ofSeconds(3)
            }
        }

        context("status") {
            val now = Instant.parse("2026-01-01T00:00:00Z")

            test("observations are recorded and a repeated reason is recognised") {
                val env = previewEnvironment("st1").apply { metadata.generation = 3 }
                val status = EnvironmentStatus(env, now, props.ingress)
                env.status.observedGeneration shouldBe 3
                env.status.namespace shouldBe "sprout-st1"
                env.status.url shouldBe "http://st1.localhost:8080"

                status.wasReason(Reason.SecretUnavailable) shouldBe false
                status.recordNotReady(Reason.SecretUnavailable, "missing")
                status.recordExpiry(now)
                env.status.expiresAt shouldBe now.toString()
                status.recordExpiry(null)
                env.status.expiresAt.shouldBeNull()
                EnvironmentStatus(env, now, props.ingress).wasReason(Reason.SecretUnavailable) shouldBe true
            }

            test("readiness is copied into the status and no url without ingress") {
                val env = previewEnvironment("st2", ingress = false)
                EnvironmentStatus(env, now, props.ingress)
                    .recordReadiness(Readiness(true, 2, Reason.WorkloadsAvailable, "ok"))
                env.status.url.shouldBeNull()
                env.status.readyReplicas shouldBe 2
                env.status.conditions
                    .single { it.type == READY }
                    .status shouldBe "True"
            }
        }

        context("cluster") {
            test("desired resources are applied and absent ones are removed") {
                val env = previewEnvironment("cl1")
                client
                    .namespaces()
                    .resource(
                        env.desiredNamespace(),
                    ).create()
                val ctx = DesiredContext(props)
                cluster.ensureAll(env, guardRails + workloads, ctx).size shouldBe 6

                val disabled = previewEnvironment("cl1", ingress = false)
                cluster.ensureAll(disabled, workloads, ctx).size shouldBe 2
                client
                    .network()
                    .v1()
                    .ingresses()
                    .inNamespace("sprout-cl1")
                    .withName("app")
                    .get()
                    .shouldBeNull()
            }
        }

        context("secrets") {
            val synchronizer = SecretSynchronizer(cluster, props)

            fun source(
                name: String,
                shareable: Boolean,
            ) = client
                .secrets()
                .inNamespace("sprout-system")
                .resource(
                    SecretBuilder()
                        .withNewMetadata()
                        .withName(name)
                        .withLabels<String, String>(if (shareable) mapOf(SHAREABLE_LABEL to "true") else emptyMap())
                        .endMetadata()
                        .withData<String, String>(mapOf("K" to "dg=="))
                        .build(),
                ).serverSideApply()

            test("no secret requested removes any stale copy") {
                val env = previewEnvironment("sec1")
                client
                    .secrets()
                    .inNamespace("sprout-sec1")
                    .resource(
                        SecretBuilder()
                            .withNewMetadata()
                            .withName(ENV_SECRET)
                            .endMetadata()
                            .build(),
                    ).serverSideApply()
                synchronizer.sync(env) shouldBe SecretOutcome.NotRequested
                client
                    .secrets()
                    .inNamespace("sprout-sec1")
                    .withName(ENV_SECRET)
                    .get()
                    .shouldBeNull()
            }

            test("a shareable secret is copied and hashed") {
                source("shared", true)
                val outcome = synchronizer.sync(previewEnvironment("sec1", secretName = "shared"))
                outcome.shouldBeInstanceOf<SecretOutcome.Copied>().hash.length shouldBe 16
                client
                    .secrets()
                    .inNamespace("sprout-sec1")
                    .withName(ENV_SECRET)
                    .get()
                    .shouldNotBeNull()
            }

            test("missing and unshareable secrets are unavailable") {
                source("hidden", false)
                synchronizer
                    .sync(previewEnvironment("sec1", secretName = "hidden"))
                    .shouldBeInstanceOf<SecretOutcome.Unavailable>()
                    .message
                    .contains("hidden") shouldBe true
                synchronizer
                    .sync(previewEnvironment("sec1", secretName = "nope"))
                    .shouldBeInstanceOf<SecretOutcome.Unavailable>()
            }
        }
    })
