package me.tommy.g1.sprout.manifest

import io.fabric8.kubernetes.api.model.Secret
import io.fabric8.kubernetes.api.model.SecretBuilder
import io.fabric8.kubernetes.api.model.networking.v1.Ingress
import io.fabric8.kubernetes.client.utils.Serialization
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import me.tommy.g1.sprout.api.EnvEntry
import me.tommy.g1.sprout.api.IngressSpec
import me.tommy.g1.sprout.api.PreviewEnvironment
import me.tommy.g1.sprout.config.SproutProperties
import me.tommy.g1.sprout.domain.ENV_SECRET
import me.tommy.g1.sprout.domain.SHAREABLE_LABEL
import me.tommy.g1.sprout.previewEnvironment

private fun secret(
    type: String?,
    shareable: Boolean,
    data: Map<String, String>? = mapOf("k" to "dg=="),
) = SecretBuilder()
    .withNewMetadata()
    .withName("s")
    .withLabels<String, String>(if (shareable) mapOf(SHAREABLE_LABEL to "true") else emptyMap())
    .endMetadata()
    .withType(type)
    .withData<String, String>(data)
    .build()

class ManifestsSpec :
    FunSpec({
        val props = SproutProperties()

        context("workloads") {
            test("desired state is deterministic and isolated per environment") {
                val env = previewEnvironment("pr-1", port = 80)
                env.desiredDeployment() shouldBe env.desiredDeployment()
                env.desiredDeployment().metadata.namespace shouldBe "sprout-pr-1"
                env.desiredService().metadata.namespace shouldBe "sprout-pr-1"
                env.desiredNamespace().metadata.name shouldBe "sprout-pr-1"
                env.desiredDeployment().spec.selector shouldNotBe
                    previewEnvironment("pr-2").desiredDeployment().spec.selector
            }

            test("a spec change only changes the affected fields") {
                val a = previewEnvironment("pr-1", image = "nginx:1").desiredDeployment()
                val b = previewEnvironment("pr-1", image = "nginx:2").desiredDeployment()
                a.metadata shouldBe b.metadata
                a.spec.selector shouldBe b.spec.selector
                a.spec.template.spec.containers
                    .single()
                    .image shouldNotBe
                    b.spec.template.spec.containers
                        .single()
                        .image
            }

            test("env vars and secret are injected only when requested") {
                val plain =
                    previewEnvironment("a")
                        .desiredDeployment()
                        .spec.template.spec.containers
                        .single()
                plain.env.shouldBeEmpty()
                plain.envFrom.shouldBeEmpty()

                val env =
                    previewEnvironment("a", secretName = "db").apply {
                        spec.env =
                            listOf(
                                EnvEntry().apply { name = "A" },
                                EnvEntry().apply {
                                    name = "B"
                                    value = "1"
                                },
                            )
                    }
                val deployment = env.desiredDeployment("abc")
                val container =
                    deployment.spec.template.spec.containers
                        .single()
                container.env.map { it.name to it.value } shouldBe listOf("A" to "", "B" to "1")
                container.envFrom
                    .single()
                    .secretRef.name shouldBe ENV_SECRET
                deployment.spec.template.metadata.annotations["sprout.dev/secret-hash"] shouldBe "abc"
            }
        }

        context("exposure") {
            test("the ingress host follows the preview domain") {
                previewEnvironment("pr-1")
                    .desiredIngress(props)
                    .spec.rules
                    .single()
                    .host shouldBe "pr-1.localhost"
            }

            test("only shareable opaque secrets are copied") {
                secret("Opaque", true).isCopyable shouldBe true
                secret(null, true).isCopyable shouldBe true
                secret("Opaque", false).isCopyable shouldBe false
                secret("kubernetes.io/service-account-token", true).isCopyable shouldBe false
                secret("Opaque", true).apply { metadata.labels = null }.isCopyable shouldBe false
            }

            test("the copy keeps the data and the hash follows it") {
                val source = secret("Opaque", true)
                previewEnvironment("a").desiredSecret(source).data shouldBe source.data
                previewEnvironment("a").desiredSecret(secret("Opaque", true, null)).data shouldBe emptyMap()
                source.contentHash() shouldBe secret("Opaque", true).contentHash()
                source.contentHash() shouldNotBe secret("Opaque", true, mapOf("k" to "dw==")).contentHash()
                secret("Opaque", true, null).contentHash() shouldNotBe source.contentHash()
            }

            test("every child kind is watched") {
                watchedKinds shouldHaveSize 7
            }
        }

        context("policies") {
            test("guard rails come from platform properties") {
                val env = previewEnvironment("a")
                env
                    .desiredQuota(props)
                    .spec.hard["pods"]!!
                    .amount shouldBe "10"
                env
                    .desiredLimitRange(props)
                    .spec.limits
                    .single()
                    .type shouldBe "Container"
                val from =
                    env
                        .desiredNetworkPolicy(props)
                        .spec.ingress
                        .single()
                        .from
                from shouldHaveSize 2
                from[1].namespaceSelector.matchLabels["kubernetes.io/metadata.name"] shouldBe "ingress-nginx"
            }
        }

        context("managed resources") {
            test("every child kind is watched and the secret is included") {
                watchedKinds shouldHaveSize 7
                watchedKinds.contains(Secret::class) shouldBe true
            }

            test("the ingress is only desired when enabled") {
                val ingress = workloads.single { it.kind == Ingress::class }
                val ctx = DesiredContext(props)
                ingress.desired(previewEnvironment("a"), ctx).shouldNotBeNull()
                ingress.desired(previewEnvironment("a", ingress = false), ctx).shouldBeNull()
            }

            test("guard rails and workloads are all built from the same environment") {
                val ctx = DesiredContext(props, "hash")
                (guardRails + workloads).forEach { it.desired(previewEnvironment("a"), ctx).shouldNotBeNull() }
            }
        }

        context("serialisation") {
            test("an explicit port survives a round trip and defaults stay unset") {
                val json =
                    """{"apiVersion":"sprout.dev/v1alpha1","kind":"PreviewEnvironment",
                |"metadata":{"name":"a"},"spec":{"image":"x","port":80}}
                    """.trimMargin()
                Serialization.unmarshal(json, PreviewEnvironment::class.java).spec.port shouldBe 80
                PreviewEnvironment().spec.port shouldBe null
                IngressSpec().enabled shouldBe null
            }
        }
    })
