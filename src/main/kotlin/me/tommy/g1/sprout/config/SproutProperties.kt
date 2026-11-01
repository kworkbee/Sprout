package me.tommy.g1.sprout.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("sprout")
class SproutProperties(
    val ingress: Ingress = Ingress(),
    val secrets: Secrets = Secrets(),
    val policy: Policy = Policy(),
    val leaderElection: LeaderElection = LeaderElection(),
    val metrics: Metrics = Metrics(),
) {
    class Ingress(
        val domain: String = "localhost",
        val port: Int? = DEFAULT_URL_PORT,
        val className: String? = null,
    )

    class Secrets(
        val sourceNamespace: String = "sprout-system",
    )

    class Policy(
        val ingressControllerNamespace: String = "ingress-nginx",
        val quota: Map<String, String> =
            mapOf(
                "pods" to "10",
                "requests.cpu" to "2",
                "requests.memory" to "2Gi",
                "limits.cpu" to "4",
                "limits.memory" to "4Gi",
            ),
        val defaultRequest: Map<String, String> = mapOf("cpu" to "100m", "memory" to "128Mi"),
        val defaultLimit: Map<String, String> = mapOf("cpu" to "500m", "memory" to "512Mi"),
        val maxLimit: Map<String, String> = mapOf("cpu" to "2", "memory" to "2Gi"),
    )

    class LeaderElection(
        val enabled: Boolean = false,
        val namespace: String = "sprout-system",
    )

    class Metrics(
        val port: Int = DEFAULT_METRICS_PORT,
    )

    private companion object {
        const val DEFAULT_URL_PORT = 8080
        const val DEFAULT_METRICS_PORT = 8081
    }
}
