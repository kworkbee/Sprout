package me.tommy.g1.sprout.reconcile

import io.fabric8.kubernetes.api.model.HasMetadata
import io.fabric8.kubernetes.api.model.Namespace
import io.fabric8.kubernetes.api.model.Secret
import io.fabric8.kubernetes.client.KubernetesClient
import me.tommy.g1.sprout.api.PreviewEnvironment
import me.tommy.g1.sprout.domain.FIELD_MANAGER
import me.tommy.g1.sprout.domain.namespaceName
import me.tommy.g1.sprout.manifest.DesiredContext
import me.tommy.g1.sprout.manifest.ManagedResource
import kotlin.reflect.KClass

interface Cluster {
    fun namespace(name: String): Namespace?

    fun secret(
        namespace: String,
        name: String,
    ): Secret?

    fun <T : HasMetadata> ensure(resource: T): T

    fun remove(
        kind: KClass<out HasMetadata>,
        namespace: String,
        name: String,
    )

    fun deleteNamespace(name: String)

    fun delete(resource: HasMetadata)
}

fun Cluster.ensureAll(
    env: PreviewEnvironment,
    resources: List<ManagedResource>,
    ctx: DesiredContext,
): List<HasMetadata> =
    resources.mapNotNull { managed ->
        managed.desired(env, ctx)?.let { ensure(it) }
            ?: run {
                remove(managed.kind, env.namespaceName, managed.name)
                null
            }
    }

class KubernetesCluster(
    private val client: KubernetesClient,
) : Cluster {
    override fun namespace(name: String): Namespace? = client.namespaces().withName(name).get()

    override fun secret(
        namespace: String,
        name: String,
    ): Secret? =
        client
            .secrets()
            .inNamespace(namespace)
            .withName(name)
            .get()

    override fun <T : HasMetadata> ensure(resource: T): T =
        client
            .resource(resource)
            .forceConflicts()
            .fieldManager(FIELD_MANAGER)
            .serverSideApply()

    @Suppress("UNCHECKED_CAST")
    override fun remove(
        kind: KClass<out HasMetadata>,
        namespace: String,
        name: String,
    ) {
        client
            .resources(kind.java as Class<HasMetadata>)
            .inNamespace(namespace)
            .withName(name)
            .delete()
    }

    override fun deleteNamespace(name: String) {
        client.namespaces().withName(name).delete()
    }

    override fun delete(resource: HasMetadata) {
        client.resource(resource).delete()
    }
}
