package me.tommy.g1.sprout.api

import io.fabric8.generator.annotation.Default
import io.fabric8.generator.annotation.Max
import io.fabric8.generator.annotation.Min
import io.fabric8.generator.annotation.Pattern
import io.fabric8.generator.annotation.Required
import io.fabric8.generator.annotation.ValidationRule
import io.fabric8.kubernetes.api.model.Condition
import io.fabric8.kubernetes.api.model.ResourceRequirements
import io.fabric8.kubernetes.client.CustomResource
import io.fabric8.kubernetes.model.annotation.Group
import io.fabric8.kubernetes.model.annotation.ShortNames
import io.fabric8.kubernetes.model.annotation.Version

@Group("sprout.dev")
@Version("v1alpha1")
@ShortNames("pe")
@ValidationRule(
    value = "self.metadata.name.matches('^[a-z0-9]([-a-z0-9]*[a-z0-9])?\$') && self.metadata.name.size() <= 56",
    message = "이름은 56자 이하의 DNS-1123 라벨이어야 합니다 ('sprout-' 접두사가 붙습니다)",
)
class PreviewEnvironment : CustomResource<PreviewEnvironmentSpec, PreviewEnvironmentStatus>() {
    override fun initSpec() = PreviewEnvironmentSpec()

    override fun initStatus() = PreviewEnvironmentStatus()
}

const val DEFAULT_PORT = 8080
private const val DEFAULT_PORT_TEXT = "$DEFAULT_PORT"

class PreviewEnvironmentSpec {
    @Required
    var image: String? = null

    @Default(DEFAULT_PORT_TEXT)
    @Min(1.0)
    @Max(65535.0)
    var port: Int? = null

    @Pattern("^[1-9][0-9]*[smhd]$")
    var ttl: String? = null

    var resources: ResourceRequirements? = null

    var env: List<EnvEntry>? = null

    @Pattern("^[a-z0-9]([-a-z0-9.]*[a-z0-9])?$")
    var secretName: String? = null

    var ingress: IngressSpec? = null
}

class EnvEntry {
    @Required
    @Pattern("^[A-Za-z_][A-Za-z0-9_]*$")
    var name: String? = null
    var value: String? = null
}

class IngressSpec {
    @Default("true")
    var enabled: Boolean? = null
}

class PreviewEnvironmentStatus {
    var observedGeneration: Long? = null
    var namespace: String? = null
    var expiresAt: String? = null
    var url: String? = null
    var readyReplicas: Int? = null
    var conditions: MutableList<Condition> = mutableListOf()
}
