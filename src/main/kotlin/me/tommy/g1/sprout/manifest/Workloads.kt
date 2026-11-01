package me.tommy.g1.sprout.manifest

import io.fabric8.kubernetes.api.model.Container
import io.fabric8.kubernetes.api.model.ContainerPort
import io.fabric8.kubernetes.api.model.EnvFromSource
import io.fabric8.kubernetes.api.model.EnvVar
import io.fabric8.kubernetes.api.model.IntOrString
import io.fabric8.kubernetes.api.model.LabelSelector
import io.fabric8.kubernetes.api.model.Namespace
import io.fabric8.kubernetes.api.model.ObjectMeta
import io.fabric8.kubernetes.api.model.PodSpec
import io.fabric8.kubernetes.api.model.PodTemplateSpec
import io.fabric8.kubernetes.api.model.SeccompProfile
import io.fabric8.kubernetes.api.model.SecretEnvSource
import io.fabric8.kubernetes.api.model.SecurityContext
import io.fabric8.kubernetes.api.model.Service
import io.fabric8.kubernetes.api.model.ServicePort
import io.fabric8.kubernetes.api.model.ServiceSpec
import io.fabric8.kubernetes.api.model.apps.Deployment
import io.fabric8.kubernetes.api.model.apps.DeploymentSpec
import me.tommy.g1.sprout.api.PreviewEnvironment
import me.tommy.g1.sprout.domain.APP_NAME
import me.tommy.g1.sprout.domain.ENV_SECRET
import me.tommy.g1.sprout.domain.SECRET_HASH_ANNOTATION
import me.tommy.g1.sprout.domain.managedLabels
import me.tommy.g1.sprout.domain.namespaceName
import me.tommy.g1.sprout.domain.podSelector
import me.tommy.g1.sprout.domain.port

fun PreviewEnvironment.desiredNamespace() = Namespace().apply { metadata = clusterMeta(namespaceName) }

fun PreviewEnvironment.desiredDeployment(secretHash: String? = null): Deployment {
    val labelsOfPods = podSelector
    val appContainer = appContainer()
    val annotationsOfPods = secretHash?.let { mapOf(SECRET_HASH_ANNOTATION to it) }.orEmpty()
    return Deployment().apply {
        metadata = childMeta(APP_NAME)
        spec =
            DeploymentSpec().apply {
                replicas = 1
                selector = LabelSelector().apply { matchLabels = labelsOfPods }
                template =
                    PodTemplateSpec().apply {
                        metadata =
                            ObjectMeta().apply {
                                labels = labelsOfPods + managedLabels
                                annotations = annotationsOfPods
                            }
                        spec =
                            PodSpec().apply {
                                automountServiceAccountToken = false
                                containers = listOf(appContainer)
                            }
                    }
            }
    }
}

private fun PreviewEnvironment.appContainer(): Container {
    val source = spec
    val appPort = port
    return Container().apply {
        name = APP_NAME
        image = source.image
        ports = listOf(ContainerPort().apply { containerPort = appPort })
        resources = source.resources
        env = source.env.orEmpty().map { EnvVar(it.name, it.value.orEmpty(), null) }
        envFrom =
            listOfNotNull(
                source.secretName?.let {
                    EnvFromSource().apply { secretRef = SecretEnvSource().apply { name = ENV_SECRET } }
                },
            )
        securityContext =
            SecurityContext().apply {
                allowPrivilegeEscalation = false
                seccompProfile = SeccompProfile().apply { type = "RuntimeDefault" }
            }
    }
}

fun PreviewEnvironment.desiredService(): Service {
    val appPort = port
    val labelsOfPods = podSelector
    return Service().apply {
        metadata = childMeta(APP_NAME)
        spec =
            ServiceSpec().apply {
                selector = labelsOfPods
                ports =
                    listOf(
                        ServicePort().apply {
                            name = "http"
                            port = appPort
                            targetPort = IntOrString(appPort)
                        },
                    )
            }
    }
}
