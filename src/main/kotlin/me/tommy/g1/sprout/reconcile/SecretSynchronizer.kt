package me.tommy.g1.sprout.reconcile

import io.fabric8.kubernetes.api.model.Secret
import me.tommy.g1.sprout.api.PreviewEnvironment
import me.tommy.g1.sprout.config.SproutProperties
import me.tommy.g1.sprout.domain.ENV_SECRET
import me.tommy.g1.sprout.domain.SHAREABLE_LABEL
import me.tommy.g1.sprout.domain.namespaceName
import me.tommy.g1.sprout.manifest.contentHash
import me.tommy.g1.sprout.manifest.desiredSecret
import me.tommy.g1.sprout.manifest.isCopyable

sealed interface SecretOutcome {
    object NotRequested : SecretOutcome

    class Copied(
        val hash: String,
    ) : SecretOutcome

    class Unavailable(
        val message: String,
    ) : SecretOutcome
}

class SecretSynchronizer(
    private val cluster: Cluster,
    private val props: SproutProperties,
) {
    fun sync(env: PreviewEnvironment): SecretOutcome {
        val name = env.spec.secretName
        val source = name?.let { cluster.secret(props.secrets.sourceNamespace, it) }
        return when {
            name == null -> {
                cluster.remove(Secret::class, env.namespaceName, ENV_SECRET)
                SecretOutcome.NotRequested
            }
            source == null || !source.isCopyable ->
                SecretOutcome.Unavailable(
                    "secret $name not found in ${props.secrets.sourceNamespace} " +
                        "or not labelled $SHAREABLE_LABEL=true and of type Opaque",
                )
            else -> {
                cluster.ensure(env.desiredSecret(source))
                SecretOutcome.Copied(source.contentHash())
            }
        }
    }
}
