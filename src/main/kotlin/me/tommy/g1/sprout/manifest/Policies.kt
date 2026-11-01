package me.tommy.g1.sprout.manifest

import io.fabric8.kubernetes.api.model.LabelSelector
import io.fabric8.kubernetes.api.model.LimitRange
import io.fabric8.kubernetes.api.model.LimitRangeItem
import io.fabric8.kubernetes.api.model.LimitRangeSpec
import io.fabric8.kubernetes.api.model.Quantity
import io.fabric8.kubernetes.api.model.ResourceQuota
import io.fabric8.kubernetes.api.model.ResourceQuotaSpec
import io.fabric8.kubernetes.api.model.networking.v1.NetworkPolicy
import io.fabric8.kubernetes.api.model.networking.v1.NetworkPolicyIngressRule
import io.fabric8.kubernetes.api.model.networking.v1.NetworkPolicyPeer
import io.fabric8.kubernetes.api.model.networking.v1.NetworkPolicySpec
import me.tommy.g1.sprout.api.PreviewEnvironment
import me.tommy.g1.sprout.config.SproutProperties

const val QUOTA_NAME = "sprout-quota"
const val LIMIT_RANGE_NAME = "sprout-limits"
const val NETWORK_POLICY_NAME = "sprout-isolation"

private fun Map<String, String>.toQuantities() = mapValues { Quantity(it.value) }

fun PreviewEnvironment.desiredQuota(props: SproutProperties) =
    ResourceQuota().apply {
        metadata = childMeta(QUOTA_NAME)
        spec = ResourceQuotaSpec().apply { hard = props.policy.quota.toQuantities() }
    }

fun PreviewEnvironment.desiredLimitRange(props: SproutProperties) =
    LimitRange().apply {
        metadata = childMeta(LIMIT_RANGE_NAME)
        spec =
            LimitRangeSpec().apply {
                limits =
                    listOf(
                        LimitRangeItem().apply {
                            type = "Container"
                            defaultRequest = props.policy.defaultRequest.toQuantities()
                            default = props.policy.defaultLimit.toQuantities()
                            max = props.policy.maxLimit.toQuantities()
                        },
                    )
            }
    }

fun PreviewEnvironment.desiredNetworkPolicy(props: SproutProperties) =
    NetworkPolicy().apply {
        metadata = childMeta(NETWORK_POLICY_NAME)
        spec =
            NetworkPolicySpec().apply {
                podSelector = LabelSelector()
                policyTypes = listOf("Ingress")
                ingress =
                    listOf(
                        NetworkPolicyIngressRule().apply {
                            from =
                                listOf(
                                    NetworkPolicyPeer().apply { podSelector = LabelSelector() },
                                    NetworkPolicyPeer().apply {
                                        namespaceSelector =
                                            LabelSelector().apply {
                                                matchLabels =
                                                    mapOf(
                                                        "kubernetes.io/metadata.name" to
                                                            props.policy.ingressControllerNamespace,
                                                    )
                                            }
                                    },
                                )
                        },
                    )
            }
    }
