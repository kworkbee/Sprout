package me.tommy.g1.sprout.manifest

import io.fabric8.kubernetes.api.model.Secret
import io.fabric8.kubernetes.api.model.networking.v1.HTTPIngressPath
import io.fabric8.kubernetes.api.model.networking.v1.HTTPIngressRuleValue
import io.fabric8.kubernetes.api.model.networking.v1.Ingress
import io.fabric8.kubernetes.api.model.networking.v1.IngressBackend
import io.fabric8.kubernetes.api.model.networking.v1.IngressRule
import io.fabric8.kubernetes.api.model.networking.v1.IngressServiceBackend
import io.fabric8.kubernetes.api.model.networking.v1.ServiceBackendPort
import me.tommy.g1.sprout.api.PreviewEnvironment
import me.tommy.g1.sprout.config.SproutProperties
import me.tommy.g1.sprout.domain.APP_NAME
import me.tommy.g1.sprout.domain.ENV_SECRET
import me.tommy.g1.sprout.domain.SHAREABLE_LABEL
import me.tommy.g1.sprout.domain.port
import me.tommy.g1.sprout.domain.previewHost
import java.security.MessageDigest
import io.fabric8.kubernetes.api.model.networking.v1.IngressSpec as IngressResourceSpec

private const val HASH_LENGTH = 16

fun PreviewEnvironment.desiredIngress(props: SproutProperties): Ingress {
    val appPort = port
    val appHost = previewHost(props.ingress.domain)
    return Ingress().apply {
        metadata = childMeta(APP_NAME)
        spec =
            IngressResourceSpec().apply {
                ingressClassName = props.ingress.className
                rules =
                    listOf(
                        IngressRule().apply {
                            host = appHost
                            http =
                                HTTPIngressRuleValue().apply {
                                    paths =
                                        listOf(
                                            HTTPIngressPath().apply {
                                                path = "/"
                                                pathType = "Prefix"
                                                backend =
                                                    IngressBackend().apply {
                                                        service =
                                                            IngressServiceBackend().apply {
                                                                name = APP_NAME
                                                                port = ServiceBackendPort().apply { number = appPort }
                                                            }
                                                    }
                                            },
                                        )
                                }
                        },
                    )
            }
    }
}

fun PreviewEnvironment.desiredSecret(source: Secret) =
    Secret().apply {
        metadata = childMeta(ENV_SECRET)
        type = "Opaque"
        data = source.data.orEmpty()
    }

val Secret.isCopyable: Boolean
    get() = (type ?: "Opaque") == "Opaque" && metadata.labels.orEmpty()[SHAREABLE_LABEL] == "true"

fun Secret.contentHash(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    data.orEmpty().toSortedMap().forEach { (key, value) -> digest.update("$key=$value;".toByteArray()) }
    return digest.digest().toHexString().take(HASH_LENGTH)
}
