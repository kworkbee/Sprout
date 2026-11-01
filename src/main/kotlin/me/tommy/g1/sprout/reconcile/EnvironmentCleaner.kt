package me.tommy.g1.sprout.reconcile

import io.javaoperatorsdk.operator.api.reconciler.DeleteControl
import me.tommy.g1.sprout.api.PreviewEnvironment
import me.tommy.g1.sprout.domain.EventType
import me.tommy.g1.sprout.domain.Reason
import me.tommy.g1.sprout.domain.SKIP_CLEANUP_WAIT
import me.tommy.g1.sprout.domain.envName
import me.tommy.g1.sprout.domain.isOwnedBy
import me.tommy.g1.sprout.domain.namespaceName
import me.tommy.g1.sprout.infra.EventPublisher
import me.tommy.g1.sprout.infra.SproutMetrics
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

private val STUCK_AFTER = 5.minutes.toJavaDuration()
private val STUCK_RECHECK = 30.seconds.toJavaDuration()
private val CLEANUP_RECHECK = 2.seconds.toJavaDuration()

class EnvironmentCleaner(
    private val cluster: Cluster,
    private val metrics: SproutMetrics,
    private val events: EventPublisher,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun cleanup(env: PreviewEnvironment): DeleteControl {
        val nsName = env.namespaceName
        val ns = cluster.namespace(nsName)
        return if (ns == null || !ns.isOwnedBy(env)) {
            DeleteControl.defaultDelete()
        } else {
            if (ns.metadata.deletionTimestamp == null) cluster.deleteNamespace(nsName)
            waitForNamespace(env, nsName)
        }
    }

    private fun waitForNamespace(
        env: PreviewEnvironment,
        nsName: String,
    ): DeleteControl {
        if (env.metadata.annotations.orEmpty()[SKIP_CLEANUP_WAIT] == "true") {
            log.warn("{}: releasing finalizer without waiting for namespace {}", env.envName, nsName)
            return DeleteControl.defaultDelete()
        }
        val waited = Duration.between(Instant.parse(env.metadata.deletionTimestamp), Instant.now())
        val stuck = waited > STUCK_AFTER
        if (stuck) reportStuck(env, nsName, waited)
        return DeleteControl.noFinalizerRemoval().rescheduleAfter(if (stuck) STUCK_RECHECK else CLEANUP_RECHECK)
    }

    private fun reportStuck(
        env: PreviewEnvironment,
        nsName: String,
        waited: Duration,
    ) {
        metrics.cleanupStuck.increment()
        events.emit(
            env,
            EventType.Warning,
            Reason.CleanupStuck,
            "namespace $nsName still terminating after ${waited.toMinutes()}m; " +
                "fix its finalizers or annotate the environment with $SKIP_CLEANUP_WAIT=true",
        )
    }
}
