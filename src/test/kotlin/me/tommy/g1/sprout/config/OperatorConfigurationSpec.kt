package me.tommy.g1.sprout.config

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import me.tommy.g1.sprout.TestCluster
import me.tommy.g1.sprout.config.SproutProperties

class OperatorConfigurationSpec :
    FunSpec({
        val config = OperatorConfiguration()
        val registry = config.meterRegistry()
        val props = SproutProperties(metrics = SproutProperties.Metrics(0))

        test("the default client can be built without contacting a cluster") {
            config.kubernetesClient().use { it.shouldNotBeNull() }
        }

        test("the operator can be wired with and without leader election") {
            val reconciler = config.reconciler(TestCluster.newClient(), props, registry)
            config.operator(TestCluster.newClient(), reconciler, props, registry).shouldNotBeNull()
            val elected = SproutProperties(leaderElection = SproutProperties.LeaderElection(true, "default"))
            config.operator(TestCluster.newClient(), reconciler, elected, registry).shouldNotBeNull()
        }

        test("the lifecycle starts the operator and the probe server, and stops them") {
            val reconciler = config.reconciler(TestCluster.newClient(), props, registry)
            val operator = config.operator(TestCluster.newClient(), reconciler, props, registry)
            val lifecycle = config.operatorLifecycle(operator, props, registry)
            lifecycle.isRunning shouldBe false
            lifecycle.start()
            lifecycle.isRunning shouldBe true
            lifecycle.stop()
            lifecycle.isRunning shouldBe false
        }
    })
