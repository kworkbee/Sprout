package me.tommy.g1.sprout.domain

import io.fabric8.kubernetes.api.model.Namespace
import me.tommy.g1.sprout.api.DEFAULT_PORT
import me.tommy.g1.sprout.api.PreviewEnvironment

const val ENV_LABEL = "sprout.dev/environment"
const val SHAREABLE_LABEL = "sprout.dev/shareable"
const val MANAGED_BY = "app.kubernetes.io/managed-by"
const val MANAGER = "sprout"
const val FIELD_MANAGER = "sprout-operator"
const val APP_NAME = "app"
const val ENV_SECRET = "app-env"
const val SECRET_HASH_ANNOTATION = "sprout.dev/secret-hash"
const val SKIP_CLEANUP_WAIT = "sprout.dev/skip-cleanup-wait"

val PreviewEnvironment.envName: String get() = metadata.name

val PreviewEnvironment.namespaceName: String get() = "sprout-$envName"

val PreviewEnvironment.port: Int get() = spec.port ?: DEFAULT_PORT

val PreviewEnvironment.ingressEnabled: Boolean get() = spec.ingress?.enabled ?: (spec.ingress != null)

val PreviewEnvironment.managedLabels: Map<String, String>
    get() = mapOf(MANAGED_BY to MANAGER, ENV_LABEL to envName)

val PreviewEnvironment.podSelector: Map<String, String>
    get() = mapOf("app.kubernetes.io/name" to APP_NAME, ENV_LABEL to envName)

fun PreviewEnvironment.previewHost(domain: String) = "$envName.$domain"

fun PreviewEnvironment.previewUrl(
    domain: String,
    port: Int?,
) = "http://${previewHost(domain)}" + port?.let { ":$it" }.orEmpty()

fun Namespace.isOwnedBy(env: PreviewEnvironment): Boolean {
    val labels = metadata.labels.orEmpty()
    return labels[MANAGED_BY] == MANAGER && labels[ENV_LABEL] == env.envName
}

enum class NamespaceState { USABLE, CONFLICT, TERMINATING }

fun Namespace?.stateFor(env: PreviewEnvironment): NamespaceState =
    when {
        this != null && !isOwnedBy(env) -> NamespaceState.CONFLICT
        this?.status?.phase == "Terminating" -> NamespaceState.TERMINATING
        else -> NamespaceState.USABLE
    }
