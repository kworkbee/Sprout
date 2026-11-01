package me.tommy.g1.sprout.config

import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.KubernetesClientBuilder
import io.javaoperatorsdk.operator.Operator
import io.javaoperatorsdk.operator.api.config.LeaderElectionConfigurationBuilder
import io.javaoperatorsdk.operator.monitoring.micrometer.MicrometerMetricsV2
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import me.tommy.g1.sprout.infra.KubernetesEventPublisher
import me.tommy.g1.sprout.infra.ObservabilityServer
import me.tommy.g1.sprout.infra.SproutMetrics
import me.tommy.g1.sprout.reconcile.EnvironmentCleaner
import me.tommy.g1.sprout.reconcile.KubernetesCluster
import me.tommy.g1.sprout.reconcile.PreviewEnvironmentReconciler
import me.tommy.g1.sprout.reconcile.SecretSynchronizer
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.SmartLifecycle
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
@ConditionalOnProperty("sprout.operator.enabled", matchIfMissing = true)
class OperatorConfiguration {
    @Bean(destroyMethod = "close")
    fun kubernetesClient(): KubernetesClient = KubernetesClientBuilder().build()

    @Bean
    fun meterRegistry() = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)

    @Bean
    fun reconciler(
        client: KubernetesClient,
        props: SproutProperties,
        registry: PrometheusMeterRegistry,
    ): PreviewEnvironmentReconciler {
        val cluster = KubernetesCluster(client)
        val metrics = SproutMetrics(registry)
        val events = KubernetesEventPublisher(client)
        return PreviewEnvironmentReconciler(
            cluster,
            props,
            metrics,
            events,
            SecretSynchronizer(cluster, props),
            EnvironmentCleaner(cluster, metrics, events),
        )
    }

    @Bean
    fun operator(
        client: KubernetesClient,
        reconciler: PreviewEnvironmentReconciler,
        props: SproutProperties,
        registry: PrometheusMeterRegistry,
    ): Operator =
        Operator { overrider ->
            overrider.withKubernetesClient(client).withMetrics(MicrometerMetricsV2.newBuilder(registry).build())
            if (props.leaderElection.enabled) {
                overrider.withLeaderElectionConfiguration(
                    LeaderElectionConfigurationBuilder
                        .aLeaderElectionConfiguration("sprout-operator")
                        .withLeaseNamespace(props.leaderElection.namespace)
                        .build(),
                )
            }
        }.apply { register(reconciler) }

    @Bean
    fun operatorLifecycle(
        operator: Operator,
        props: SproutProperties,
        registry: PrometheusMeterRegistry,
    ): SmartLifecycle = OperatorLifecycle(operator, ObservabilityServer(registry, props.metrics.port))
}

class OperatorLifecycle(
    private val operator: Operator,
    private val probes: ObservabilityServer,
) : SmartLifecycle {
    @Volatile
    private var running = false

    override fun start() {
        probes.start()
        operator.start()
        probes.markReady()
        running = true
    }

    override fun stop() {
        probes.stop()
        operator.stop()
        running = false
    }

    override fun isRunning() = running
}
