package me.tommy.g1.sprout.manifest

import io.fabric8.kubernetes.api.model.HasMetadata
import io.fabric8.kubernetes.api.model.LimitRange
import io.fabric8.kubernetes.api.model.ResourceQuota
import io.fabric8.kubernetes.api.model.Secret
import io.fabric8.kubernetes.api.model.Service
import io.fabric8.kubernetes.api.model.apps.Deployment
import io.fabric8.kubernetes.api.model.networking.v1.Ingress
import io.fabric8.kubernetes.api.model.networking.v1.NetworkPolicy
import me.tommy.g1.sprout.api.PreviewEnvironment
import me.tommy.g1.sprout.config.SproutProperties
import me.tommy.g1.sprout.domain.APP_NAME
import me.tommy.g1.sprout.domain.ingressEnabled
import kotlin.reflect.KClass

class DesiredContext(
    val props: SproutProperties,
    val secretHash: String? = null,
)

interface ManagedResource {
    val kind: KClass<out HasMetadata>
    val name: String

    fun desired(
        env: PreviewEnvironment,
        ctx: DesiredContext,
    ): HasMetadata?
}

private inline fun <reified T : HasMetadata> managed(
    name: String,
    crossinline build: PreviewEnvironment.(DesiredContext) -> T?,
) = object : ManagedResource {
    override val kind = T::class
    override val name = name

    override fun desired(
        env: PreviewEnvironment,
        ctx: DesiredContext,
    ) = env.build(ctx)
}

val guardRails: List<ManagedResource> =
    listOf(
        managed<ResourceQuota>(QUOTA_NAME) { desiredQuota(it.props) },
        managed<LimitRange>(LIMIT_RANGE_NAME) { desiredLimitRange(it.props) },
        managed<NetworkPolicy>(NETWORK_POLICY_NAME) { desiredNetworkPolicy(it.props) },
    )

val workloads: List<ManagedResource> =
    listOf(
        managed<Service>(APP_NAME) { desiredService() },
        managed<Deployment>(APP_NAME) { desiredDeployment(it.secretHash) },
        managed<Ingress>(APP_NAME) { if (ingressEnabled) desiredIngress(it.props) else null },
    )

val watchedKinds: List<KClass<out HasMetadata>> = (guardRails + workloads).map { it.kind } + Secret::class
