package me.tommy.g1.sprout

import io.fabric8.kubeapitest.KubeAPIServer
import io.fabric8.kubernetes.client.Config
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.KubernetesClientBuilder
import java.io.File

object TestCluster {
    private val api = KubeAPIServer().also { it.start() }

    init {
        newClient().use { it.load(File("config/crd/previewenvironments.sprout.dev-v1.yml").inputStream()).create() }
        Runtime.getRuntime().addShutdownHook(Thread { api.stop() })
    }

    /** Operator.stop() closes its client, so nothing is shared: every user gets a client of its own. */
    fun newClient(): KubernetesClient =
        KubernetesClientBuilder().withConfig(Config.fromKubeconfig(api.kubeConfigYaml)).build()
}
