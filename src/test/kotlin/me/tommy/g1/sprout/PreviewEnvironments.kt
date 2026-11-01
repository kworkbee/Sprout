package me.tommy.g1.sprout

import io.fabric8.kubernetes.api.model.ObjectMetaBuilder
import io.fabric8.kubernetes.api.model.ResourceRequirements
import me.tommy.g1.sprout.api.IngressSpec
import me.tommy.g1.sprout.api.PreviewEnvironment

fun previewEnvironment(
    name: String,
    image: String = "nginx:1",
    port: Int? = null,
    ttl: String? = null,
    secretName: String? = null,
    ingress: Boolean = true,
    resources: ResourceRequirements? = null,
) = PreviewEnvironment().apply {
    metadata = ObjectMetaBuilder().withName(name).build()
    spec.image = image
    spec.port = port
    spec.ttl = ttl
    spec.secretName = secretName
    spec.ingress = if (ingress) IngressSpec() else null
    spec.resources = resources
}
