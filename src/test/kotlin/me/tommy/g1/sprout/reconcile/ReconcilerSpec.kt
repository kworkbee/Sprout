package me.tommy.g1.sprout.reconcile

import io.fabric8.kubernetes.api.model.Namespace
import io.fabric8.kubernetes.api.model.NamespaceBuilder
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder
import io.fabric8.kubernetes.api.model.Quantity
import io.fabric8.kubernetes.api.model.ResourceRequirementsBuilder
import io.fabric8.kubernetes.api.model.SecretBuilder
import io.fabric8.kubernetes.client.KubernetesClient
import io.javaoperatorsdk.operator.Operator
import io.javaoperatorsdk.operator.api.reconciler.Context
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import io.mockk.mockk
import me.tommy.g1.sprout.TestCluster
import me.tommy.g1.sprout.api.PreviewEnvironment
import me.tommy.g1.sprout.config.SproutProperties
import me.tommy.g1.sprout.domain.APP_NAME
import me.tommy.g1.sprout.domain.ENV_LABEL
import me.tommy.g1.sprout.domain.ENV_SECRET
import me.tommy.g1.sprout.domain.MANAGED_BY
import me.tommy.g1.sprout.domain.MANAGER
import me.tommy.g1.sprout.domain.READY
import me.tommy.g1.sprout.domain.SHAREABLE_LABEL
import me.tommy.g1.sprout.domain.SKIP_CLEANUP_WAIT
import me.tommy.g1.sprout.infra.KubernetesEventPublisher
import me.tommy.g1.sprout.infra.SproutMetrics
import me.tommy.g1.sprout.previewEnvironment
import java.time.Instant
import kotlin.time.Duration.Companion.seconds

private val props = SproutProperties(ingress = SproutProperties.Ingress("test.local", 8080, "nginx"))
private val client: KubernetesClient = TestCluster.newClient()
private val crs = client.resources(PreviewEnvironment::class.java)
private val context = mockk<Context<PreviewEnvironment>>(relaxed = true)

private fun reason(name: String) =
    crs
        .withName(name)
        .get()
        ?.status
        ?.conditions
        ?.singleOrNull()
        ?.reason

private fun deployment(ns: String) =
    client
        .apps()
        .deployments()
        .inNamespace(ns)
        .withName(APP_NAME)
        .get()

private fun ownedNamespace(env: String): Namespace =
    NamespaceBuilder()
        .withNewMetadata()
        .withName("sprout-$env")
        .withLabels<String, String>(mapOf(MANAGED_BY to MANAGER, ENV_LABEL to env))
        .endMetadata()
        .build()

class ReconcilerSpec :
    FunSpec({
        val registry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
        val metrics = SproutMetrics(registry)
        val cluster = KubernetesCluster(client)
        val events = KubernetesEventPublisher(client)
        val reconciler =
            PreviewEnvironmentReconciler(
                cluster,
                props,
                metrics,
                events,
                SecretSynchronizer(cluster, props),
                EnvironmentCleaner(cluster, metrics, events),
            )
        val operator = Operator { it.withKubernetesClient(client) }.apply { register(reconciler) }

        beforeSpec {
            client
                .namespaces()
                .resource(
                    NamespaceBuilder()
                        .withNewMetadata()
                        .withName(props.secrets.sourceNamespace)
                        .endMetadata()
                        .build(),
                ).serverSideApply()
            val secrets = client.secrets().inNamespace(props.secrets.sourceNamespace)
            secrets
                .resource(
                    SecretBuilder()
                        .withNewMetadata()
                        .withName("db")
                        .withLabels<String, String>(mapOf(SHAREABLE_LABEL to "true"))
                        .endMetadata()
                        .withData<String, String>(mapOf("PASSWORD" to "cHc="))
                        .build(),
                ).serverSideApply()
            secrets
                .resource(
                    SecretBuilder()
                        .withNewMetadata()
                        .withName("private")
                        .endMetadata()
                        .withData<String, String>(mapOf("K" to "dg=="))
                        .build(),
                ).serverSideApply()
            operator.start()
        }

        afterSpec { operator.stop() }

        test("an environment gets its namespace, guard rails and workloads, and reports status") {
            crs
                .resource(
                    previewEnvironment(
                        "a1",
                        secretName = "db",
                        resources = ResourceRequirementsBuilder().addToRequests("cpu", Quantity("100m")).build(),
                    ),
                ).create()
            eventually(30.seconds) {
                val ns = "sprout-a1"
                client
                    .namespaces()
                    .withName(ns)
                    .get()
                    .shouldNotBeNull()
                deployment(ns).shouldNotBeNull()
                client
                    .services()
                    .inNamespace(ns)
                    .withName(APP_NAME)
                    .get()
                    .shouldNotBeNull()
                client
                    .resourceQuotas()
                    .inNamespace(ns)
                    .withName("sprout-quota")
                    .get()
                    .shouldNotBeNull()
                client
                    .limitRanges()
                    .inNamespace(ns)
                    .withName("sprout-limits")
                    .get()
                    .shouldNotBeNull()
                client
                    .network()
                    .v1()
                    .networkPolicies()
                    .inNamespace(ns)
                    .withName("sprout-isolation")
                    .get()
                    .shouldNotBeNull()
                client
                    .network()
                    .v1()
                    .ingresses()
                    .inNamespace(ns)
                    .withName(APP_NAME)
                    .get()
                    .spec.rules
                    .single()
                    .host shouldBe "a1.test.local"
                client
                    .secrets()
                    .inNamespace(ns)
                    .withName(ENV_SECRET)
                    .get()
                    .data["PASSWORD"] shouldBe "cHc="
                val cr = crs.withName("a1").get()
                cr.status.url shouldBe "http://a1.test.local:8080"
                cr.status.observedGeneration shouldBe cr.metadata.generation
                cr.status.conditions
                    .single { it.type == READY }
                    .reason shouldBe "Progressing"
            }
            crs
                .withName("a1")
                .get()
                .metadata.finalizers
                .shouldNotBeNull()
        }

        test("a deleted child is recreated and a spec change updates it in place") {
            crs.resource(previewEnvironment("a2")).create()
            eventually(30.seconds) { deployment("sprout-a2").shouldNotBeNull() }
            val first = deployment("sprout-a2").metadata.uid

            client
                .apps()
                .deployments()
                .inNamespace("sprout-a2")
                .withName(APP_NAME)
                .delete()
            eventually(30.seconds) { deployment("sprout-a2").shouldNotBeNull().metadata.uid shouldNotBe first }

            val second = deployment("sprout-a2").metadata.uid
            crs.resource(previewEnvironment("a2", image = "nginx:2")).patch()
            eventually(30.seconds) {
                val d = deployment("sprout-a2")
                d.spec.template.spec.containers
                    .single()
                    .image shouldBe "nginx:2"
                d.metadata.uid shouldBe second
            }
        }

        test("an environment without ingress or secret has neither") {
            crs.resource(previewEnvironment("a3", ingress = false)).create()
            eventually(30.seconds) {
                deployment("sprout-a3").shouldNotBeNull()
                crs
                    .withName("a3")
                    .get()
                    .status.url
                    .shouldBeNull()
            }
            client
                .network()
                .v1()
                .ingresses()
                .inNamespace("sprout-a3")
                .withName(APP_NAME)
                .get()
                .shouldBeNull()
            client
                .secrets()
                .inNamespace("sprout-a3")
                .withName(ENV_SECRET)
                .get()
                .shouldBeNull()
        }

        test("an unshareable secret blocks the workloads but not the guard rails") {
            crs.resource(previewEnvironment("a4", secretName = "private")).create()
            eventually(30.seconds) { reason("a4") shouldBe "SecretUnavailable" }
            deployment("sprout-a4").shouldBeNull()
            client
                .resourceQuotas()
                .inNamespace("sprout-a4")
                .withName("sprout-quota")
                .get()
                .shouldNotBeNull()
        }

        test("a missing secret is reported, and the retry never outlives the ttl") {
            crs.resource(previewEnvironment("a8", secretName = "missing", ttl = "1h")).create()
            eventually(30.seconds) { reason("a8") shouldBe "SecretUnavailable" }
            crs
                .withName("a8")
                .get()
                .status.expiresAt
                .shouldNotBeNull()
        }

        test("a foreign namespace is never adopted or deleted") {
            client
                .namespaces()
                .resource(
                    NamespaceBuilder()
                        .withNewMetadata()
                        .withName("sprout-a5")
                        .endMetadata()
                        .build(),
                ).create()
            crs.resource(previewEnvironment("a5")).create()
            eventually(30.seconds) { reason("a5") shouldBe "NamespaceConflict" }
            deployment("sprout-a5").shouldBeNull()
            crs.withName("a5").delete()
            eventually(30.seconds) { crs.withName("a5").get().shouldBeNull() }
            client
                .namespaces()
                .withName("sprout-a5")
                .get()
                .shouldNotBeNull()
        }

        test("a namespace that starts terminating pauses reconciliation") {
            crs.resource(previewEnvironment("a6")).create()
            eventually(30.seconds) { deployment("sprout-a6").shouldNotBeNull() }
            client.namespaces().withName("sprout-a6").delete()
            eventually(30.seconds) { reason("a6") shouldBe "NamespaceTerminating" }
        }

        test("ttl expiry deletes the environment; cleanup waits for the namespace unless told to stop") {
            crs.resource(previewEnvironment("a7", ttl = "3s")).create()
            eventually(30.seconds) {
                client
                    .namespaces()
                    .withName("sprout-a7")
                    .get()
                    ?.metadata
                    ?.deletionTimestamp
                    .shouldNotBeNull()
            }
            eventually(10.seconds) {
                crs
                    .withName("a7")
                    .get()
                    .metadata.deletionTimestamp
                    .shouldNotBeNull()
            }
            crs.withName("a7").edit {
                it.metadata.annotations = mapOf(SKIP_CLEANUP_WAIT to "true")
                it
            }
            eventually(30.seconds) { crs.withName("a7").get().shouldBeNull() }
            metrics.expired.count() shouldBe 1.0
        }

        test("the schema rejects invalid names and ttls") {
            shouldThrowAny { crs.resource(previewEnvironment("Bad.Name")).create() }
            shouldThrowAny { crs.resource(previewEnvironment("x".repeat(57))).create() }
            shouldThrowAny { crs.resource(previewEnvironment("ok", ttl = "forever")).create() }
        }

        test("an invalid ttl that got past the schema is reported without touching the cluster") {
            val env = previewEnvironment("bogus", ttl = "forever").apply { metadata.generation = 1 }
            reconciler.reconcile(env, context).isPatchStatus shouldBe true
            env.status.conditions
                .single()
                .reason shouldBe "InvalidTTL"
        }

        test("cleanup only touches namespaces it owns") {
            previewEnvironment("nothing").apply { metadata.annotations = null }.let {
                reconciler.cleanup(it, context).isRemoveFinalizer shouldBe true
            }
            reconciler.cleanup(previewEnvironment("nothing"), context).isRemoveFinalizer shouldBe true

            client
                .namespaces()
                .resource(
                    NamespaceBuilder()
                        .withNewMetadata()
                        .withName("sprout-foreign")
                        .endMetadata()
                        .build(),
                ).create()
            reconciler.cleanup(previewEnvironment("foreign"), context).isRemoveFinalizer shouldBe true
            client
                .namespaces()
                .withName("sprout-foreign")
                .get()
                .shouldNotBeNull()
        }

        test("cleanup waits, escalates after a while and can be told to stop waiting") {
            client.namespaces().resource(ownedNamespace("slow")).create()
            val env =
                previewEnvironment("slow").apply {
                    metadata = ObjectMetaBuilder(metadata).withDeletionTimestamp(Instant.now().toString()).build()
                    metadata.annotations = null
                }
            reconciler.cleanup(env, context).isRemoveFinalizer shouldBe false
            eventually(10.seconds) {
                client
                    .namespaces()
                    .withName("sprout-slow")
                    .get()
                    .metadata.deletionTimestamp
                    .shouldNotBeNull()
            }

            val stuck =
                previewEnvironment("slow").apply {
                    metadata =
                        ObjectMetaBuilder(
                            metadata,
                        ).withDeletionTimestamp(Instant.now().minusSeconds(600).toString()).build()
                }
            reconciler.cleanup(stuck, context).isRemoveFinalizer shouldBe false
            metrics.cleanupStuck.count() shouldBe 1.0

            stuck.metadata.annotations = mapOf(SKIP_CLEANUP_WAIT to "true")
            reconciler.cleanup(stuck, context).isRemoveFinalizer shouldBe true
        }

        test("a foreign namespace is counted as a conflict") {
            metrics.conflicts.count() shouldNotBe 0.0
        }
    })
